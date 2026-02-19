package swyp.paperdot.document.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import swyp.paperdot.doc_units.docUnits.docUnitsEntity;
import swyp.paperdot.doc_units.docUnits.docUnitsRepository;
import swyp.paperdot.doc_units.enums.UnitStatus;
import swyp.paperdot.doc_units.enums.UnitType;
import swyp.paperdot.doc_units.exception.DocUnitsAlreadyExistException;
import swyp.paperdot.doc_units.translation.DocUnitTranslation;
import swyp.paperdot.doc_units.translation.DocUnitTranslationRepository;
import swyp.paperdot.document.dto.DocumentTranslationPairResponse;
import swyp.paperdot.document.dto.DocumentTranslationProgressResponse;
import swyp.paperdot.translator.OpenAiTranslator;
import swyp.paperdot.translator.dto.OpenAiTranslationDto.TranslationPair;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
/**
 * 문서 번역 파이프라인의 오케스트레이션 서비스.
 *
 * <p>한 문서(documentId)에 대해 "PDF 다운로드 -> OCR/번역 -> DB 저장 -> 조회" 흐름을 한 곳에서 조율한다.
 * 문장 단위 저장은 DOC_UNITS 테이블, 번역문 저장은 DocUnitTranslation 테이블로 분리되어 있으며
 * 이 서비스는 두 테이블 간 매핑(orderInDoc, FK)을 일관되게 유지하는 역할을 담당한다.
 */
public class DocumentPipelineService {
    // 운영 로그 기준 시각을 통일하기 위해 KST 기준으로 타임스탬프를 남긴다.
    private static final ZoneId KST_ZONE = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    // 현재 파이프라인의 기본 번역 타겟 언어. 필요 시 호출부에서 파라미터화할 수 있다.
    private static final String DEFAULT_TARGET_LANG = "ko";

    private final DocumentDownloadService documentDownloadService;
    private final docUnitsRepository docUnitsRepository;
    private final OpenAiTranslator openAiTranslator;
    private final DocUnitTranslationRepository docUnitTranslationRepository;

    /**
     * 단일 문서에 대한 전체 파이프라인 본 처리.
     *
     * <p>실행 순서:
     * 1) PDF를 스토리지에서 내려받아 OpenAI Vision 처리
     * 2) 생성된 번역 쌍을 DOC_UNITS/번역 테이블에 저장
     *
     * <p>주의:
     * - 여기서 발생한 예외는 감싸서 다시 던지며, 상위 비동기 호출부에서 실패로 기록된다.
     * - 최종적으로 START/END 로그와 경과 시간을 항상 남긴다.
     *
     * @param documentId 처리 대상 문서 ID
     * @param overwrite 기존 결과를 덮어쓸지 여부
     */
    public void processDocument(Long documentId, boolean overwrite) {
        Instant startedAt = Instant.now();
        log.info("===== Document Pipeline START for documentId: {} (Overwrite: {}) at {} =====",
                documentId, overwrite, nowKst());

        try {
            // Step 1:
            // PDF를 다운로드한 뒤 Vision 기반 처리로 (원문, 번역문) 쌍을 생성한다.
            log.info("[Step 1/2] documentId {} - Downloading PDF and calling Vision API", documentId);
            List<TranslationPair> translationPairs;
            try (InputStream pdfInputStream = documentDownloadService.downloadOriginalPdf(documentId)) {
                translationPairs = openAiTranslator.processPdfAndTranslate(pdfInputStream, DEFAULT_TARGET_LANG);
            }

            if (CollectionUtils.isEmpty(translationPairs)) {
                throw new IllegalStateException("OpenAI returned no translation pairs. documentId: " + documentId);
            }
            log.info("[Step 1/2] documentId {} - Vision API processing complete. {} sentence pairs generated.",
                    documentId, translationPairs.size());

            // Step 2:
            // 생성된 번역 쌍을 DB 스키마(DOC_UNITS + DocUnitTranslation)에 맞춰 저장한다.
            log.info("[Step 2/2] documentId {} - Saving original text and translations to DB. Overwrite: {}",
                    documentId, overwrite);
            saveTranslationsAndDocUnits(documentId, translationPairs, DEFAULT_TARGET_LANG, overwrite);
            log.info("[Step 2/2] documentId {} - DB save complete.", documentId);

        } catch (Exception e) {
            // 호출부(특히 @Async 진입점)에서 단일 예외 타입으로 처리하기 쉽도록 감싸서 전파한다.
            log.error("===== Document Pipeline FAILED for documentId: {} =====", documentId, e);
            throw new RuntimeException("Failed to process documentId: " + documentId, e);
        } finally {
            log.info("===== Document Pipeline END for documentId: {} at {} (elapsedSec={}) =====",
                    documentId, nowKst(), elapsedSeconds(startedAt));
        }
    }

    /**
     * OpenAI 결과를 DB 스키마에 맞게 저장한다.
     *
     * <p>저장 정책:
     * - overwrite=true: 기존 DOC_UNITS/번역 데이터 삭제 후 재생성
     * - overwrite=false: 기존 데이터가 있으면 예외 발생
     *
     * <p>정합성 포인트:
     * - source/translated가 비어있는 pair는 저장 제외
     * - orderInDoc를 기준으로 doc_unit과 번역을 1:1 매핑
     *
     * <p>트랜잭션:
     * - 메서드 전체가 하나의 트랜잭션으로 동작하여 중간 저장 상태를 남기지 않는다.
     *
     * @param documentId 처리 대상 문서 ID
     * @param translationPairs Vision 처리 결과(원문/번역문 쌍)
     * @param targetLang 저장할 번역 언어 코드(예: ko, en)
     * @param overwrite true면 기존 결과 삭제 후 재저장, false면 기존 데이터 존재 시 예외
     */
    @Transactional
    public void saveTranslationsAndDocUnits(Long documentId,
                                            List<TranslationPair> translationPairs,
                                            String targetLang,
                                            boolean overwrite) {
        log.info("saveTranslationsAndDocUnits start: documentId {}, {} pairs", documentId, translationPairs.size());

        if (translationPairs.isEmpty()) {
            log.warn("documentId {} - no translation pairs to save.", documentId);
            return;
        }

        // 재처리 요청이면 기존 결과를 먼저 정리한다.
        // FK 제약을 고려해 "번역 -> 원문(doc_units)" 순서로 삭제한다.
        if (overwrite) {
            log.info("documentId {} - overwrite enabled: deleting existing doc units/translations.", documentId);
            docUnitTranslationRepository.deleteByDocUnitDocumentId(documentId);
            docUnitsRepository.deleteByDocumentId(documentId);
        } else if (docUnitsRepository.existsByDocumentId(documentId)) {
            throw new DocUnitsAlreadyExistException(
                    "DocUnit rows already exist. Retry with overwrite=true. documentId: " + documentId);
        }

        List<TranslationPair> validPairs = new ArrayList<>();
        for (TranslationPair pair : translationPairs) {
            // 번역 품질/완성도를 보장하기 위해 원문 또는 번역문이 비어있는 항목은 제외한다.
            if (pair == null || pair.source() == null || pair.source().isBlank()) {
                log.warn("documentId {} - skipping pair with empty source.", documentId);
                continue;
            }
            if (pair.translated() == null || pair.translated().isBlank()) {
                log.warn("documentId {} - skipping pair with empty translated text. source: {}",
                        documentId, pair.source());
                continue;
            }
            validPairs.add(pair);
        }

        if (validPairs.isEmpty()) {
            log.warn("documentId {} - no valid translation pairs after validation.", documentId);
            return;
        }

        List<docUnitsEntity> newDocUnits = new ArrayList<>();
        int progressLogInterval = Math.max(1, validPairs.size() / 10);

        // 1차 저장 모델(DOC_UNITS) 생성.
        // 이 시점에 orderInDoc(0-based)를 고정해 이후 번역 레코드와의 매핑 기준으로 사용한다.
        for (int i = 0; i < validPairs.size(); i++) {
            TranslationPair pair = validPairs.get(i);
            docUnitsEntity docUnit = docUnitsEntity.builder()
                    .documentId(documentId)
                    .sourceText(pair.source())
                    .status(UnitStatus.TRANSLATED)
                    .unitType(UnitType.SENTENCE)
                    .orderInDoc(i)
                    .build();
            newDocUnits.add(docUnit);

            if ((validPairs.size() > 10 && (i + 1) % progressLogInterval == 0) || (i + 1) == validPairs.size()) {
                double progress = ((double) (i + 1) / validPairs.size()) * 100;
                log.info("documentId {} - save progress: {}/{} ({}%)",
                        documentId, i + 1, validPairs.size(), Math.round(progress));
            }
        }

        // 먼저 DOC_UNITS를 저장해야 생성된 PK(id)를 번역 row의 FK로 연결할 수 있다.
        docUnitsRepository.saveAll(newDocUnits);
        log.info("documentId {} - saved {} doc units.", documentId, newDocUnits.size());

        List<DocUnitTranslation> newTranslations = new ArrayList<>();
        // orderInDoc 기반으로 같은 인덱스의 번역 텍스트를 매핑한다.
        // validPairs를 필터링한 뒤 동일 순서로 docUnit을 생성했기 때문에 인덱스 일치가 보장된다.
        for (docUnitsEntity docUnit : newDocUnits) {
            TranslationPair matchingPair = validPairs.get(docUnit.getOrderInDoc());
            DocUnitTranslation translation = DocUnitTranslation.builder()
                    .docUnit(docUnit)
                    .targetLang(targetLang)
                    .translatedText(matchingPair.translated())
                    .build();
            newTranslations.add(translation);
        }

        docUnitTranslationRepository.saveAll(newTranslations);
        log.info("documentId {} - saved {} translation rows.", documentId, newTranslations.size());
    }

    /**
     * 비동기 진입점.
     *
     * <p>컨트롤러는 이 메서드를 호출하고 즉시 202를 반환한다.
     * 실제 파이프라인 성공/실패는 이 메서드 로그로 추적한다.
     *
     * <p>비동기 스레드에서는 호출자에게 예외를 직접 전달할 수 없으므로,
     * 여기서는 예외를 재던지지 않고 실패 로그를 남겨 중복 스택트레이스 출력을 줄인다.
     *
     * @param documentId 처리 대상 문서 ID
     * @param overwrite 기존 결과를 덮어쓸지 여부
     */
    @Async
    public void processDocumentAsync(Long documentId, boolean overwrite) {
        Instant asyncStartedAt = Instant.now();
        log.info("[Async Start] documentId {} processing started at {}. Overwrite: {}",
                documentId, nowKst(), overwrite);
        boolean success = false;
        try {
            processDocument(documentId, overwrite);
            success = true;
        } catch (Exception e) {
            // SimpleAsyncUncaughtExceptionHandler와 중복 출력되는 스택트레이스를 줄이기 위한 처리.
            log.error("[Async Fail] documentId {} processing failed. Overwrite: {}", documentId, overwrite, e);
        }
        log.info("[Async End] documentId {} processing finished at {}. success={}, elapsedSec={}",
                documentId, nowKst(), success, elapsedSeconds(asyncStartedAt));
    }

    /**
     * 저장된 DOC_UNITS와 번역 테이블을 결합해 API 응답 DTO로 변환한다.
     *
     * <p>조회 순서는 orderInDoc ASC를 유지하여 문서 원문 흐름이 보존된다.
     * 번역 row가 누락된 문장은 translatedText를 빈 문자열로 채워 응답 형태를 안정적으로 유지한다.
     *
     * @param documentId 조회 대상 문서 ID
     * @return 문장 순서(orderInDoc)가 보장된 원문/번역문 쌍 목록
     */
    @Transactional(readOnly = true)
    public List<DocumentTranslationPairResponse> getTranslationPairsForDocument(Long documentId) {
        List<docUnitsEntity> docUnits = docUnitsRepository.findByDocumentIdOrderByOrderInDocAsc(documentId);
        if (CollectionUtils.isEmpty(docUnits)) {
            log.warn("documentId {} - no doc units found.", documentId);
            return Collections.emptyList();
        }

        List<DocUnitTranslation> translations = docUnitTranslationRepository.findByDocUnitDocumentId(documentId);
        Map<Long, String> translatedTextMap = translations.stream().collect(Collectors.toMap(
                dt -> dt.getDocUnit().getId(),
                DocUnitTranslation::getTranslatedText
        ));

        return docUnits.stream()
                .map(docUnit -> DocumentTranslationPairResponse.builder()
                        .docUnitId(docUnit.getId())
                        .sourceText(docUnit.getSourceText())
                        .translatedText(translatedTextMap.getOrDefault(docUnit.getId(), ""))
                        .build())
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public DocumentTranslationProgressResponse getTranslationProgress(Long documentId) {
        long total = docUnitsRepository.countByDocumentId(documentId);
        long translated = docUnitsRepository.countByDocumentIdAndStatus(documentId, UnitStatus.TRANSLATED);
        long translating = docUnitsRepository.countByDocumentIdAndStatus(documentId, UnitStatus.TRANSLATING);
        long created = docUnitsRepository.countByDocumentIdAndStatus(documentId, UnitStatus.CREATED);
        long failed = docUnitsRepository.countByDocumentIdAndStatus(documentId, UnitStatus.FAILED);

        return DocumentTranslationProgressResponse.builder()
                .total(total)
                .translated(translated)
                .translating(translating)
                .created(created)
                .failed(failed)
                .build();
    }

    private String nowKst() {
        return ZonedDateTime.now(KST_ZONE).format(TS_FORMAT);
    }

    private long elapsedSeconds(Instant start) {
        return Duration.between(start, Instant.now()).toSeconds();
    }
}
