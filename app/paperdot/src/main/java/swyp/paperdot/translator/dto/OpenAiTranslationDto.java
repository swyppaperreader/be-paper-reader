package swyp.paperdot.translator.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

// OpenAI Chat Completions API와의 통신을 위한 DTO들을 정의합니다.
public class OpenAiTranslationDto {

    // --- Standard Chat Request DTOs ---

    public record ChatRequest(
            String model,
            List<Message> messages,
            @JsonProperty("response_format") ResponseFormat responseFormat
    ) {
        public static ChatRequest of(String model, List<Message> messages, ResponseFormat responseFormat) {
            return new ChatRequest(model, messages, responseFormat);
        }

        public static ChatRequest of(String model, List<Message> messages) {
            return new ChatRequest(model, messages, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            String role,
            String content
    ) {}

    public record ResponseFormat(
            String type
    ) {}

    // --- Vision Chat Request DTOs ---

    public record VisionChatRequest(
            String model,
            List<VisionMessage> messages,
            @JsonProperty("max_completion_tokens") int maxCompletionTokens
    ) {}

    public record VisionMessage(
            String role,
            List<ContentPart> content
    ) {}

    // Using a sealed interface for type-safe content parts
    public sealed interface ContentPart {
        public record TextContentPart(String type, String text) implements ContentPart {}
        public record ImageContentPart(String type, @JsonProperty("image_url") ImageUrl imageUrl) implements ContentPart {}
    }

    public record ImageUrl(
            String url
    ) {}


    // --- Common Response DTOs ---

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ChatResponse {
        private List<Choice> choices;
    }

    @Getter
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Choice {
        private Message message; // Vision 응답도 이 구조를 따름
        @JsonProperty("finish_reason")
        private String finishReason;
    }

    // --- Translation Result DTO ---

    public record TranslationPair(String source, String translated) {}
}
