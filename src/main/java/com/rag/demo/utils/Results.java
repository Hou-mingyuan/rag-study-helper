package com.rag.demo.utils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用于返回，根据返回码判断状态
 * 200=成功
 * 500=失败
 */
@AllArgsConstructor
@NoArgsConstructor
@Data
public class Results<T> {
    // 失败
    public static final String ERROR = "500";
    // 成功
    public static final String SUCCESS = "200";

    /**
     * 返回码
     */
    private String resCode;

    /**
     * 返回消息
     */
    private String msg;

    /**
     * 返回实体
     */
    private T obj;

    public static <T> Results<T> success() {
        return success(SUCCESS, "成功", null);
    }

    public static <T> Results<T> success(String msg) {
        return success(SUCCESS, msg, null);
    }

    public static <T> Results<T> success(T obj) {
        return success(SUCCESS, "成功", obj);
    }

    public static <T> Results<T> success(String msg, T obj) {
        return success(SUCCESS, msg, obj);
    }

    public static <T> Results<T> success(String resCode, String msg, T obj) {
        Results<T> result = new Results<T>();
        result.setResCode(resCode);
        result.setMsg(msg);
        result.setObj(obj);
        return result;
    }

    public static <T> Results<T> failed() {
        return failed(ERROR, "失败", null);
    }

    public static <T> Results<T> failed(String msg) {
        return failed(ERROR, msg, null);
    }

    public static <T> Results<T> failed(String msg, T obj) {
        return failed(ERROR, msg, obj);
    }

    public static <T> Results<T> failed(String resCode, String msg) {
        return failed(resCode, msg, null);
    }

    public static <T> Results<T> failed(Integer resCode, String msg) {
        return failed(String.valueOf(resCode), msg);
    }

    public static <T> Results<T> failed(String resCode, String msg, T obj) {
        Results<T> result = new Results<T>();
        result.setResCode(resCode);
        result.setMsg(msg);
        result.setObj(obj);
        return result;
    }

}
