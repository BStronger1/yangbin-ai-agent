package com.yangbin.yangbinaiagent.controller;

import com.yangbin.yangbinaiagent.router.SmartRouterService;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/ai/router")
public class RouterController {
    private final SmartRouterService router;
    public RouterController(SmartRouterService router) { this.router = router; }
    @GetMapping("/status")
    public Map<String,Object> status() { return router.status(); }
}
