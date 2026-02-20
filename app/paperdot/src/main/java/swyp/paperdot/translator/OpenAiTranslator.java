package swyp.paperdot.translator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import swyp.paperdot.translator.dto.OpenAiTranslationDto;
import swyp.paperdot.translator.dto.OpenAiTranslationDto.ImageUrl;
import swyp.paperdot.translator.dto.OpenAiTranslationDto.TranslationPair;
import swyp.paperdot.translator.dto.OpenAiTranslationDto.VisionChatRequest;
import swyp.paperdot.translator.dto.OpenAiTranslationDto.VisionMessage;
import swyp.paperdot.translator.exception.TranslationException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Component
/**
 * OpenAI Chat Completions를 사용하는 번역 컴포넌트입니다.
 * 역할은 크게 두 가지입니다.
 * 1) PDF 페이지 이미지 기반 OCR + 번역(Vision 경로)
 * 2) 레거시 원문 텍스트 번역 경로(현재 비활성)
 *
 * Vision 경로는 페이지 단위로 처리하며, 기본 DPI로 전체 처리 후 타임아웃 페이지만
 * 낮은 DPI로 재시도합니다. 재시도 후에도 실패 페이지가 남으면 전체를 실패 처리합니다.
 */
public class OpenAiTranslator implements TranslatorPort {
    private static final DateTimeFormatter LOG_TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Spring 설정에서 주입되는 OpenAI 인증/모델 값
    private final String apiKey;
    private final String model;
    // 페이지 처리 동시성(기본값 3). API 제한/서버 자원 상황에 따라 조정 가능.
    private final int visionParallelism;
    // 1차 시도 DPI (기본 120)
    private final int visionDefaultDpi;
    // 타임아웃 페이지 재시도 DPI (기본 96)
    private final int visionTimeoutRetryDpi;
    // 이미지 인식용 Vision 모델(현재 고정)
    private final String visionModel = "gpt-4o";
    // 텍스트/비전 모두 Chat Completions 엔드포인트 사용
    private final String apiUrl = "https://api.openai.com/v1/chat/completions";

    public OpenAiTranslator(
            ObjectMapper objectMapper,
            @Value("${openai.api.key}") String apiKey,
            @Value("${openai.api.model}") String model,
            @Value("${openai.api.vision.parallelism:3}") int visionParallelism,
            @Value("${openai.api.vision.default-dpi:120}") int visionDefaultDpi,
            @Value("${openai.api.vision.timeout-retry-dpi:96}") int visionTimeoutRetryDpi
    ) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
        this.visionParallelism = Math.max(1, visionParallelism);
        this.visionDefaultDpi = Math.max(72, visionDefaultDpi);
        this.visionTimeoutRetryDpi = Math.max(72, visionTimeoutRetryDpi);
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                // 연결 타임아웃: TCP 연결 수립 단계에만 적용
                .connectTimeout(Duration.ofSeconds(20))
                .build();
    }

    /**
     * PDF를 페이지 이미지로 변환한 뒤, 각 페이지를 OpenAI Vision에 보내고
     * 번역 결과(원문-번역 쌍)를 하나의 리스트로 합칩니다.
     *
     * 예외 처리 정책:
     * - 1차(기본 DPI) 처리에서 타임아웃인 페이지만 2차(낮은 DPI)로 재시도
     * - 재시도 후에도 실패 페이지가 있으면 전체를 실패로 처리(페이지 누락 방지)
     * - PDF 자체를 읽거나 렌더링하는 실패만 IOException으로 전파
     *
     * @param pdfInputStream 입력 PDF 스트림
     * @param targetLang 목표 번역 언어(코드/이름)
     * @return 모든 페이지가 성공했을 때만 번역 쌍 누적 결과
     * @throws IOException PDF 읽기/렌더링 실패 시
     */
    public List<TranslationPair> processPdfAndTranslate(InputStream pdfInputStream, String targetLang) throws IOException {
        long overallStartNanos = System.nanoTime();
        log.info("Starting PDF processing with Vision API (page-by-page). Target language: {}", targetLang);

        byte[] pdfBytes = pdfInputStream.readAllBytes();
        List<ImageUrl> base64Images = convertPdfToImages(pdfBytes, visionDefaultDpi);
        if (base64Images.isEmpty()) {
            long totalElapsedMs = (System.nanoTime() - overallStartNanos) / 1_000_000;
            log.warn("No images were extracted from the PDF. Returning empty list. elapsedMs: {}", totalElapsedMs);
            return Collections.emptyList();
        }
        int totalPages = base64Images.size();
        log.info("Converted PDF to {} pages (images) with dpi: {}.", totalPages, visionDefaultDpi);

        List<PageTask> firstPassTasks = new ArrayList<>();
        for (int i = 0; i < totalPages; i++) {
            firstPassTasks.add(new PageTask(i, base64Images.get(i)));
        }

        List<PageTranslationResult> firstPassResults = processPageTasksInParallel(firstPassTasks, totalPages, targetLang, visionDefaultDpi, false);
        PageTranslationResult[] finalResultsByPage = new PageTranslationResult[totalPages];
        for (PageTranslationResult result : firstPassResults) {
            finalResultsByPage[result.pageIndex()] = result;
        }

        List<Integer> timeoutPageIndexes = firstPassResults.stream()
                .filter(result -> result.status() == PageStatus.TIMED_OUT)
                .map(PageTranslationResult::pageIndex)
                .toList();

        if (!timeoutPageIndexes.isEmpty()) {
            List<PageTask> retryTasks = new ArrayList<>();
            for (Integer pageIndex : timeoutPageIndexes) {
                retryTasks.add(new PageTask(pageIndex, convertPdfPageToImage(pdfBytes, pageIndex, visionTimeoutRetryDpi)));
            }

            log.warn("Retrying timed-out pages with lower dpi. retryCount: {}, retryDpi: {}, pages: {}",
                    retryTasks.size(), visionTimeoutRetryDpi, timeoutPageIndexes.stream().map(i -> i + 1).toList());

            List<PageTranslationResult> retryResults = processPageTasksInParallel(retryTasks, totalPages, targetLang, visionTimeoutRetryDpi, true);
            for (PageTranslationResult retryResult : retryResults) {
                finalResultsByPage[retryResult.pageIndex()] = retryResult;
            }
        }

        List<PageTranslationResult> failedResults = new ArrayList<>();
        for (PageTranslationResult result : finalResultsByPage) {
            if (result == null || result.status() != PageStatus.SUCCESS) {
                failedResults.add(result);
            }
        }

        if (!failedResults.isEmpty()) {
            String failedPages = failedResults.stream()
                    .map(result -> {
                        if (result == null) {
                            return "unknown_page";
                        }
                        return (result.pageIndex() + 1) + "(" + result.status() + ":" + result.detail() + ")";
                    })
                    .collect(Collectors.joining(", "));
            long totalElapsedMs = (System.nanoTime() - overallStartNanos) / 1_000_000;
            log.error("Translation failed because some pages were not completed. failedPages: {}, elapsedMs: {}", failedPages, totalElapsedMs);
            throw new TranslationException("Failed to translate all pages. failedPages: " + failedPages);
        }

        List<TranslationPair> allTranslationPairs = new ArrayList<>();
        for (PageTranslationResult result : finalResultsByPage) {
            allTranslationPairs.addAll(result.pairs());
        }

        long totalElapsedMs = (System.nanoTime() - overallStartNanos) / 1_000_000;
        log.info("Finished processing all pages. Total pairs found: {}, elapsedMs: {}", allTranslationPairs.size(), totalElapsedMs);
        return allTranslationPairs;
    }

    private List<PageTranslationResult> processPageTasksInParallel(
            List<PageTask> tasks,
            int totalPages,
            String targetLang,
            int dpi,
            boolean retryAttempt
    ) {
        if (tasks.isEmpty()) {
            return Collections.emptyList();
        }

        int workerCount = Math.min(visionParallelism, tasks.size());
        log.info("Processing pages in parallel. workers: {}, taskCount: {}, dpi: {}, retryAttempt: {}",
                workerCount, tasks.size(), dpi, retryAttempt);

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<CompletableFuture<PageTranslationResult>> futures = new ArrayList<>();
            for (PageTask task : tasks) {
                futures.add(CompletableFuture.supplyAsync(
                        () -> processSinglePage(task.pageIndex(), totalPages, task.pageImage(), targetLang, dpi, retryAttempt),
                        executor
                ));
            }

            return futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(PageTranslationResult::pageIndex))
                    .toList();
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 단일 페이지를 Vision API에 전달해 번역 쌍을 추출합니다.
     * 실패 시 상태값을 반환하고, 상위 레벨에서 재시도/실패 판단을 수행합니다.
     */
    private PageTranslationResult processSinglePage(
            int pageIndex,
            int totalPages,
            ImageUrl pageImage,
            String targetLang,
            int dpi,
            boolean retryAttempt
    ) {
        long pageStartNanos = System.nanoTime();
        String startedAt = LocalDateTime.now().format(LOG_TIME_FORMAT);
        log.info("Processing page {}/{}, startedAt: {}, dpi: {}, retryAttempt: {}...",
                pageIndex + 1, totalPages, startedAt, dpi, retryAttempt);

        // 멀티모달 입력 구성: 지시문 텍스트 + 페이지 이미지 1장
        List<OpenAiTranslationDto.ContentPart> contentParts = new ArrayList<>();
        contentParts.add(new OpenAiTranslationDto.ContentPart.TextContentPart("text", createPerPageVisionPrompt(targetLang)));
        contentParts.add(new OpenAiTranslationDto.ContentPart.ImageContentPart("image_url", pageImage));

        VisionMessage visionMessage = new VisionMessage("user", contentParts);
        VisionChatRequest requestBody = new VisionChatRequest(visionModel, List.of(visionMessage), 4096);

        try {
            String requestJson = objectMapper.writeValueAsString(requestBody);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofMinutes(2))
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                    .build();

            // 페이지 단위 실패 처리 단순화를 위해 동기 호출 사용
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                long elapsedMs = (System.nanoTime() - pageStartNanos) / 1_000_000;
                log.error("Failed to call OpenAI Vision API for page {}. Status: {}, Body: {}. elapsedMs: {}. dpi: {}, retryAttempt: {}.",
                        pageIndex + 1, response.statusCode(), response.body(), elapsedMs, dpi, retryAttempt);
                return PageTranslationResult.failed(pageIndex, "HTTP_" + response.statusCode());
            }

            OpenAiTranslationDto.ChatResponse chatResponse = objectMapper.readValue(response.body(), OpenAiTranslationDto.ChatResponse.class);

            if (chatResponse == null || CollectionUtils.isEmpty(chatResponse.getChoices())) {
                long elapsedMs = (System.nanoTime() - pageStartNanos) / 1_000_000;
                log.warn("Received empty or invalid response from OpenAI for page {}. elapsedMs: {}. dpi: {}, retryAttempt: {}.",
                        pageIndex + 1, elapsedMs, dpi, retryAttempt);
                return PageTranslationResult.failed(pageIndex, "EMPTY_RESPONSE");
            }

            // 모델 응답에 설명 텍스트가 섞여도 JSON 배열만 방어적으로 추출
            String rawContent = chatResponse.getChoices().get(0).getMessage().content();
            List<TranslationPair> pairsForPage = parseOpenAiResponseForPairs(rawContent);
            long elapsedMs = (System.nanoTime() - pageStartNanos) / 1_000_000;
            String finishedAt = LocalDateTime.now().format(LOG_TIME_FORMAT);
            log.info("Successfully processed page {}/{}, found {} pairs, elapsedMs: {}, finishedAt: {}, dpi: {}, retryAttempt: {}.",
                    pageIndex + 1, totalPages, pairsForPage.size(), elapsedMs, finishedAt, dpi, retryAttempt);
            return PageTranslationResult.success(pageIndex, pairsForPage);

        } catch (JsonProcessingException e) {
            long elapsedMs = (System.nanoTime() - pageStartNanos) / 1_000_000;
            log.error("Failed to serialize request for page {}. Error: {}. elapsedMs: {}. dpi: {}, retryAttempt: {}.",
                    pageIndex + 1, e.getMessage(), elapsedMs, dpi, retryAttempt);
            return PageTranslationResult.failed(pageIndex, "JSON_SERIALIZE_ERROR");
        } catch (IOException e) {
            long elapsedMs = (System.nanoTime() - pageStartNanos) / 1_000_000;
            if (isTimeoutException(e)) {
                log.error("I/O timeout while calling OpenAI for page {}. Error: {}. elapsedMs: {}. dpi: {}, retryAttempt: {}.",
                        pageIndex + 1, e.getMessage(), elapsedMs, dpi, retryAttempt);
                return PageTranslationResult.timedOut(pageIndex, e.getMessage());
            }
            log.error("I/O error while calling OpenAI for page {}. Error: {}. elapsedMs: {}. dpi: {}, retryAttempt: {}.",
                    pageIndex + 1, e.getMessage(), elapsedMs, dpi, retryAttempt);
            return PageTranslationResult.failed(pageIndex, "IO_ERROR");
        } catch (InterruptedException e) {
            long elapsedMs = (System.nanoTime() - pageStartNanos) / 1_000_000;
            log.error("API call for page {} was interrupted. Error: {}. elapsedMs: {}. dpi: {}, retryAttempt: {}.",
                    pageIndex + 1, e.getMessage(), elapsedMs, dpi, retryAttempt);
            Thread.currentThread().interrupt(); // 인터럽트 상태 복원
            return PageTranslationResult.failed(pageIndex, "INTERRUPTED");
        } catch (TranslationException e) {
            long elapsedMs = (System.nanoTime() - pageStartNanos) / 1_000_000;
            log.error("Failed to parse response for page {}. Error: {}. elapsedMs: {}. dpi: {}, retryAttempt: {}.",
                    pageIndex + 1, e.getMessage(), elapsedMs, dpi, retryAttempt);
            return PageTranslationResult.failed(pageIndex, "PARSE_ERROR");
        }
    }

    /**
     * PDF 각 페이지를 PNG로 렌더링하고, OpenAI image_url 형식에 맞는
     * Base64 Data URL로 변환합니다.
     *
     * @param pdfBytes PDF 바이너리
     * @param dpi 렌더링 DPI
     * @return 페이지별 image_url 리스트
     * @throws IOException PDF 로딩/이미지 인코딩 실패 시
     */
    private List<ImageUrl> convertPdfToImages(byte[] pdfBytes, int dpi) throws IOException {
        List<ImageUrl> base64Images = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            for (int page = 0; page < document.getNumberOfPages(); ++page) {
                base64Images.add(renderPageAsImageUrl(pdfRenderer, page, dpi));
            }
        }
        return base64Images;
    }

    private ImageUrl convertPdfPageToImage(byte[] pdfBytes, int pageIndex, int dpi) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            return renderPageAsImageUrl(pdfRenderer, pageIndex, dpi);
        }
    }

    private ImageUrl renderPageAsImageUrl(PDFRenderer pdfRenderer, int page, int dpi) throws IOException {
        BufferedImage bim = pdfRenderer.renderImageWithDPI(page, dpi);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(bim, "png", baos);
        String base64 = Base64.getEncoder().encodeToString(baos.toByteArray());
        return new ImageUrl("data:image/png;base64," + base64);
    }

    /**
     * 페이지 단위 OCR/문장 분리/번역 지시 프롬프트를 생성합니다.
     * 후처리 단순화를 위해 출력 형식을 "JSON 배열"로 강하게 제한합니다.
     */
    private String createPerPageVisionPrompt(String targetLang) {
        return String.format(
            "You are an expert OCR and translation engine. Analyze this single document page image. " +
            "Perform the following tasks: " +
            "1. Extract all text content in proper reading order. " +
            "2. Segment the extracted text into logical sentences. " +
            "3. Translate each sentence into %s. " +
            "Your final output MUST be a single, valid JSON array of objects. Each object MUST contain two keys: 'source' for the original logical sentence, and 'translated' for its corresponding translation. " +
            "If the page is blank or contains no text, return an empty array []. " +
            "Do NOT include any additional text, explanations, or markdown formatting outside of the JSON array. " +
            "Example: [{\"source\": \"This is a sentence.\", \"translated\": \"이것은 문장입니다.\"}]",
            targetLang
        );
    }

    /**
     * TranslatorPort 호환을 위해 남겨둔 레거시 메서드입니다.
     * 현재 PDF 파이프라인에서는 이 경로를 사용하지 않습니다.
     */
    public List<TranslationPair> extractAndTranslate(String rawText, String targetLang) {
        log.warn("Legacy method extractAndTranslate was called. This method is not using the native HttpClient.");
        return Collections.emptyList();
    }

    /**
     * 레거시 원문 텍스트 번역 경로에서 사용하는 시스템 프롬프트 생성기입니다.
     * 현재는 미사용이지만, 복구 가능성을 위해 유지합니다.
     */
    private String createSystemPrompt(String targetLang) {
        return String.format(
            "You are a translator that takes raw text, splits it into logical sentences, and translates each sentence into %s. " +
            "The response MUST be a JSON array of objects. Each object MUST contain two keys: 'source' for an original logical sentence and 'translated' for its corresponding translated sentence. " +
            "Do NOT include any additional text, explanations, or markdown formatting outside the JSON array.",
            targetLang
        );
    }

    /**
     * 모델 응답에서 TranslationPair JSON 배열을 추출하고 파싱합니다.
     *
     * 프롬프트 제약과 달리 JSON 외 텍스트가 함께 올 수 있어,
     * 가장 바깥 배열 경계를 찾아 복구 파싱합니다.
     * 유효한 배열 경계를 찾지 못하면 예외 대신 빈 리스트를 반환합니다.
     *
     * @param content assistant 원문 응답 문자열
     * @return 파싱된 번역 쌍 리스트(실패 시 빈 리스트)
     * @throws TranslationException 배열 경계는 찾았지만 역직렬화에 실패한 경우
     */
    private List<TranslationPair> parseOpenAiResponseForPairs(String content) {
        if (content == null || content.isBlank()) {
            log.warn("Response content is null or blank.");
            return Collections.emptyList();
        }

        // 보수적 복구 전략: 첫 '[' 와 마지막 ']' 를 배열 경계로 사용
        int startIndex = content.indexOf("[");
        int endIndex = content.lastIndexOf("]");

        if (startIndex == -1 || endIndex == -1 || endIndex < startIndex) {
            log.warn("Could not find a valid JSON array in the response. Content: {}", content);
            return Collections.emptyList();
        }

        String jsonArrayString = content.substring(startIndex, endIndex + 1);

        try {
            TypeReference<List<TranslationPair>> typeRef = new TypeReference<>() {};
            return objectMapper.readValue(jsonArrayString, typeRef);
        } catch (JsonProcessingException e) {
            throw new TranslationException("Failed to parse JSON response from OpenAI: " + jsonArrayString, e);
        }
    }

    private boolean isTimeoutException(IOException e) {
        if (e instanceof HttpTimeoutException) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.toLowerCase(Locale.ROOT).contains("timed out");
    }

    private enum PageStatus {
        SUCCESS,
        TIMED_OUT,
        FAILED
    }

    private record PageTask(int pageIndex, ImageUrl pageImage) {}

    private record PageTranslationResult(int pageIndex, List<TranslationPair> pairs, PageStatus status, String detail) {
        static PageTranslationResult success(int pageIndex, List<TranslationPair> pairs) {
            return new PageTranslationResult(pageIndex, pairs, PageStatus.SUCCESS, "OK");
        }

        static PageTranslationResult timedOut(int pageIndex, String detail) {
            return new PageTranslationResult(pageIndex, Collections.emptyList(), PageStatus.TIMED_OUT, detail);
        }

        static PageTranslationResult failed(int pageIndex, String detail) {
            return new PageTranslationResult(pageIndex, Collections.emptyList(), PageStatus.FAILED, detail);
        }
    }
}
