package com.example.cachedb.sample.web;

import com.example.cachedb.sample.application.ops.TuningQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/tuning")
public class TuningController {

    private final TuningQueryService tuning;

    public TuningController(TuningQueryService tuning) {
        this.tuning = tuning;
    }

    @GetMapping
    public TuningQueryService.TuningResponse current() {
        return tuning.current();
    }

    @GetMapping("/profiles")
    public List<TuningQueryService.PolicyProfile> profiles() {
        return tuning.profiles();
    }
}
