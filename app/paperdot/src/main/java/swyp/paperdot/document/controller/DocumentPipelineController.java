package swyp.paperdot.document.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import swyp.paperdot.document.service.DocumentHistoryService;
import swyp.paperdot.document.service.DocumentPipelineService;

import java.util.Map;

@Slf4j
@Tag(name = "문서 처리 파이프라인", description = "문서의 전체 처리 과정을 관리하는 API")
@RestController
@RequestMapping("/api/v1/documents")
@RequiredArgsConstructor
public class DocumentPipelineController {

    private final DocumentPipelineService documentPipelineService;
    private final DocumentHistoryService documentHistoryService;

    @Operation(summary = "문서 처리 파이프라인 실행", description = "특정 문서 ID에 대해 텍스트 추출, 번역, 저장 파이프라인을 비동기로 실행합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "202", description = "파이프라인 처리 시작"),
            @ApiResponse(responseCode = "404", description = "문서 ID가 존재하지 않음"),
            @ApiResponse(responseCode = "500", description = "파이프라인 실행 중 서버 오류")
    })
    @PostMapping("/{documentId}/process")
    public ResponseEntity<Map<String, String>> processDocument(
            @Parameter(description = "처리할 문서의 ID", required = true) @PathVariable Long documentId,
            @Parameter(description = "기존에 처리된 'doc_units'가 있을 경우 덮어쓸지 여부. 기본값은 false.")
            @RequestParam(defaultValue = "false") boolean overwrite
    ) {
        log.info("API 요청: documentId {} 문서를 처리 요청 받음. Overwrite: {}", documentId, overwrite);
        documentPipelineService.processDocumentAsync(documentId, overwrite);
        return ResponseEntity.accepted().body(Map.of("message", "Document processing initiated for documentId: " + documentId));
    }

    @Operation(summary = "문서 번역 쌍 조회", description = "특정 문서의 원문-번역 문장 쌍을 1:1로 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "번역 쌍 조회 성공"),
            @ApiResponse(responseCode = "404", description = "문서 ID가 없거나 번역 데이터가 없음")
    })
    @GetMapping("/{documentId}/translation-pairs")
    public ResponseEntity<java.util.List<swyp.paperdot.document.dto.DocumentTranslationPairResponse>> getTranslationPairs(
            @Parameter(description = "번역 쌍을 조회할 문서 ID", required = true) @PathVariable Long documentId
    ) {
        log.info("API 요청: documentId {} 번역 쌍 조회 요청 받음.", documentId);
        java.util.List<swyp.paperdot.document.dto.DocumentTranslationPairResponse> translationPairs =
                documentPipelineService.getTranslationPairsForDocument(documentId);

        if (translationPairs.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(translationPairs);
    }

    @Operation(summary = "번역 진행률 조회", description = "문서 번역 진행률(상태별 개수)을 조회합니다.")
    @GetMapping("/{documentId}/translation-progress")
    public ResponseEntity<swyp.paperdot.document.dto.DocumentTranslationProgressResponse> getTranslationProgress(
            @Parameter(description = "진행률을 조회할 문서 ID", required = true) @PathVariable Long documentId
    ) {
        return ResponseEntity.ok(documentPipelineService.getTranslationProgress(documentId));
    }

    @Operation(summary = "번역 기록 목록 조회", description = "사용자(ownerId)의 번역 완료 문서 목록을 조회합니다.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "번역 기록 목록 조회 성공"),
            @ApiResponse(responseCode = "400", description = "ownerId 누락")
    })
    @GetMapping("/translation-histories")
    public ResponseEntity<java.util.List<swyp.paperdot.document.dto.DocumentTranslationHistoryItemResponse>> getTranslationHistories(
            @Parameter(description = "문서 소유자 ID", required = true) @RequestParam Long ownerId
    ) {
        return ResponseEntity.ok(documentHistoryService.getTranslationHistory(ownerId));
    }
}
