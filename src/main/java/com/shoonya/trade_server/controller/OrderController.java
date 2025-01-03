package com.shoonya.trade_server.controller;

import com.shoonya.trade_server.lib.ShoonyaHelper;
import com.shoonya.trade_server.service.OrderManagementService;
import com.shoonya.trade_server.service.TradeManagementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class OrderController {
    private TradeManagementService tradeManagementService;
    private Logger logger = LoggerFactory.getLogger(OrderController.class.getName());
    private ShoonyaHelper shoonyaHelper;
    private OrderManagementService orderManagementService;
    public OrderController(ShoonyaHelper shoonyaHelper, TradeManagementService tradeManagementService,
                           OrderManagementService orderManagementService){
        this.shoonyaHelper = shoonyaHelper;
        this.tradeManagementService = tradeManagementService;
        this.orderManagementService = orderManagementService;
    }

    @PostMapping("/buyOrder/{symbol}/{price}")
    public ResponseEntity<String> buyOrder(@PathVariable String symbol, @PathVariable double price) {
        logger.info("buy symbol {} at price {}", symbol, price);
        LocalDateTime currentTime = LocalDateTime.now();
        LocalDateTime lastTradeTime = tradeManagementService.getLastbuyTime();
        long minutesPassed = Duration.between(lastTradeTime, currentTime ).toMinutes();
        long timeRemaining = 15 - minutesPassed;

        if (timeRemaining > 0)
            return ResponseEntity.status(HttpStatus.NOT_ACCEPTABLE).body(timeRemaining + "");
        // place market order if price is 0
        if(price == 0.0)
        shoonyaHelper.placeOrder("B", "M", "NFO", symbol, 150, "MKT"
                    , 0.0, 0.0);
        else
            shoonyaHelper.placeOrder("B", "M", "NFO", symbol, 150, "LMT"
                    , price, 0.0);
        return ResponseEntity.ok("order placed");

    }

    @GetMapping("/openOrders")
    public List<Map<String, Object>> getOpenOrders() {
        return tradeManagementService.getOpenOrders();
    }

    @PostMapping("/cancelOrder/{norenordno}")
    public ResponseEntity<String > cancelOrder(@PathVariable String norenordno){
        shoonyaHelper.cancelOrderNo(norenordno);
        return ResponseEntity.ok("order cancelled");
    }

    @PostMapping("/modifyOrder/{norenordno}/{newPrice}")
    public ResponseEntity<String > modifyOrder(@PathVariable String norenordno, @PathVariable Double newPrice){
        orderManagementService.modifyOrder(norenordno, newPrice);
        return ResponseEntity.ok("order modofied");
    }

}
