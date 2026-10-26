package com.lazybone.hazmat.msds.ai;

/**
 * 向量算不出来。调用方只记失败次数并暂停，不把异常抛给检索接口：
 * 字面检索不依赖向量，MSDS 检索必须继续可用。
 */
public class AiUnavailableException extends RuntimeException {

    public AiUnavailableException(String message) {
        super(message);
    }

    public AiUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
