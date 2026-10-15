package com.yangbin.yangbinimagesearchmcpserver;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
@RestController
public class ReadyController {
    @GetMapping("/ready") public String ready(){return "ok";}
}
