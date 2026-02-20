package swyp.paperdot.translator.exception;

// OpenAI API 호출 실패(네트워크, 인증, 서버 오류 등) 시 발생하는 예외
public class TranslationException extends RuntimeException {
    public TranslationException(String message) {
        super(message);
    }

    public TranslationException(String message, Throwable cause) {
        super(message, cause);
    }
}
