package com.rag.demo.config;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test")
class ExceptionTestController {

    @GetMapping("/illegal-argument")
    public void throwIllegalArgument() {
        throw new IllegalArgumentException("参数错误");
    }

    @GetMapping("/no-such-element")
    public void throwNoSuchElement() {
        throw new java.util.NoSuchElementException("资源不存在");
    }

    @GetMapping("/unknown-error")
    public void throwUnknown() {
        throw new RuntimeException("内部异常");
    }
}
