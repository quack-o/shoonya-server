package com.shoonya.trade_server.service;
import com.neovisionaries.ws.client.WebSocketException;
import com.shoonya.trade_server.config.IntradayConfig;
import com.shoonya.trade_server.config.ShoonyaConfig;
import com.shoonya.trade_server.lib.ShoonyaHelper;
import com.noren.javaapi.NorenApiJava;
import com.shoonya.trade_server.entity.PartialTrade;
import com.shoonya.trade_server.entity.TokenInfo;
import com.shoonya.trade_server.lib.Misc;
import com.shoonya.trade_server.lib.ShoonyaWebSocket;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.lang.Math.max;

@Getter
class TradeManager{

    public Map<String, Map<String, PartialTrade>> trades;

    public TradeManager() {
        trades = new HashMap<>();
    }
    public void addTrade(String token, String tradeName, PartialTrade trade) {
        trades.computeIfAbsent(token, k -> new HashMap<>()).put(tradeName, trade);
    }

    // Method to retrieve a trade
    public Map<String, PartialTrade> getTrade(String token) {
        return trades.getOrDefault(token, new HashMap<>());
    }

    public boolean removeTrade(String token){
        Map<String, PartialTrade> trade = trades.get(token);
        if(trade != null)
            return trades.remove(token, trade);
        return false;
    }

    public void updateTrade(String token, String pt, PartialTrade partialTrade){
        trades.get(token).put(pt, partialTrade);
    }

    public boolean hasToken(String token){
        return trades.containsKey(token);
    }
}

@Getter
@Service
public class TradeManagementService {

    private static final Logger logger = LogManager.getLogger(TradeManagementService.class.getName());

    private ShoonyaWebSocket wsClient;
    private boolean feedOpened = false;
    private  final Map<String, Map<String, Object>> feedJson = new HashMap<>();
    private  final Map<String, Double> ltps = new HashMap<>();
    private List <String> subscribedTokens = new ArrayList<>();

    private List<Map<String, Object>> openOrders = new ArrayList<>();

    private LocalDateTime lastbuyTime = LocalDateTime.now().minusDays(1);
    TradeManager tradeManager;

    Misc misc;

    ShoonyaHelper shoonyaHelper;
    RiskManagementService riskManagementService;
    ShoonyaConfig shoonyaConfig;
    NorenApiJava api;

    private IntradayConfig intradayConfig;
    private List<IntradayConfig.Index> indexes;


    public TradeManagementService(ShoonyaHelper shoonyaHelper, Misc misc, RiskManagementService riskManagementService,
                                  ShoonyaConfig shoonyaConfig, IntradayConfig intradayConfig,
                                  ShoonyaLoginService shoonyaLoginService){
        this.shoonyaHelper = shoonyaHelper;
        this.tradeManager = new TradeManager();
        this.misc = misc;
        this.riskManagementService = riskManagementService;
        this.api = shoonyaLoginService.getApi();
        this.shoonyaConfig = shoonyaConfig;
        this.indexes = intradayConfig.getIndexes();
    }


    public void updateOpenOrders(){
        this.openOrders =  new ArrayList<>();
        JSONArray orders =  shoonyaHelper.getOpenOrders();
        for (int i = 0; i < orders.length(); i++) {
            JSONObject order = orders.getJSONObject(i);
            openOrders.add(order.toMap());
        }
    }

    public void eventHandlerOrderUpdate(JSONObject orderUpdate){
        logger.info("order feed {}", orderUpdate);
        try {
            updateOrder(wsClient, orderUpdate);
            updateOpenOrders();
        } catch (java.lang.Exception e) {
            logger.error("update order error occured {}", e.getMessage());
            // Log with the specific line number
            StackTraceElement element = e.getStackTrace()[0];
            logger.error("Error occurred at line: {}", element.getLineNumber());
        }
    }


    public void eventHandlerFeedUpdate(JSONObject tickData) {
        boolean UPDATE = false;

        if (tickData.has("tk")) {
            String token = tickData.getString("tk");
            LocalDateTime timest = LocalDateTime.ofInstant(Instant.ofEpochSecond(tickData.getLong("ft")), ZoneOffset.UTC);
            Map<String, Object> feedData = new HashMap<>();
            feedData.put("tt", timest.toString()); // ISO format

            if (tickData.has("lp")) {
                feedData.put("ltp", tickData.getDouble("lp"));
            }
            if (tickData.has("ts")) {
                feedData.put("Tsym", tickData.getString("ts"));
            }
            if (tickData.has("oi")) {
                feedData.put("openi", tickData.getDouble("oi"));
            }
            if (tickData.has("poi")) {
                feedData.put("pdopeni", tickData.getString("poi"));
            }
            if (tickData.has("v")) {
                feedData.put("Volume", tickData.getString("v"));
            }

            if (!feedData.isEmpty()) {
                UPDATE = true;
                feedJson.putIfAbsent(token, new HashMap<>());
                feedJson.get(token).putAll(feedData);
            }

            if (UPDATE) {
                if (feedData.containsKey("ltp")) {
                    try {
                        ltps.put(token, Double.parseDouble(feedJson.get(token).get("ltp").toString()));

                        manageOptionSl(token, ltps.get(token));
                    } catch (Exception e) {
                        logger.error("Error with feed occurred: {}", e.getMessage());
                    }
                }
            }
        }
    }

    public void openCallback(){
        this.feedOpened = true;
        logger.info("websocket opened");
    }

    @PostConstruct
    public void startWebsocket() throws Exception {

        String websocketEndpoint = shoonyaConfig.getWebsocket();
        ShoonyaWebSocket.WebSocketHandler handler = new ShoonyaWebSocket.WebSocketHandler() {
            @Override
            public void onTextMessage(String message) {
//                logger.info("Message received: {}" , message);
                JSONObject res = new JSONObject(message);

                //feed update
                if (res.getString("t") .equals("tk") || res.getString("t") .equals("tf"))
                    eventHandlerFeedUpdate(res);
                if (res.getString("t") .equals("dk") || res.getString("t") .equals("df"))
                    eventHandlerFeedUpdate(res);

                // feed order update
                if (res.getString("t") .equals("om"))
                    eventHandlerOrderUpdate(res);

                // feed started
                if (res.getString("t") .equals("ck") && res.getString("s") .equals("OK"))
                    openCallback();

                // feed error
                if (res.getString("t") .equals("ck") && !res.getString("s") .equals("OK"))
                    logger.error("Error with feed {}", res);
                // Log with the specific line number
            }


            @Override
            public void onError(WebSocketException cause) {
                logger.error("Error occurred: {}" , cause.getMessage());
            }
        };
        this.wsClient = new ShoonyaWebSocket(websocketEndpoint, this.api, handler);
//        client = new ShoonyaWebSocketNeo(websocketEndpoint, api);

        this.wsClient.connect();
        //TODO: use asynchronous? what is the use
        while (!this.feedOpened)
            TimeUnit.SECONDS.sleep(1);

    }

    @PostConstruct
    public void subscribeIndexes(){
        for(IntradayConfig.Index index:indexes){
            String token = index.getToken();
            String name = index.getName();
            String exch = index.getExchange();
            TokenInfo tokenInfo = new TokenInfo(exch, token, null);

            String instrument = tokenInfo.getInstrument();
            this.wsClient.subscribe(instrument, NorenApiJava.FeedType.TOUCHLINE);
            logger.info("subscribed to index {}", name );
        }
    }

    public void subscribe(TokenInfo tokenInfo){
        String instrument = tokenInfo.getInstrument();
        if( !subscribedTokens.contains(instrument)) {
            subscribedTokens.add(instrument);
            this.wsClient.subscribe(instrument, NorenApiJava.FeedType.TOUCHLINE);
            logger.info("subscribed to {}", instrument);
        }
    }

    public void unsubscribe(TokenInfo tokenInfo){
        String instrument = tokenInfo.getInstrument();
        this.wsClient.unsubscribe(instrument, NorenApiJava.FeedType.TOUCHLINE);
        logger.info("unsubscribed from {}", instrument );
    }




    public void createTrade(String token, JSONObject orderUpdate){

        int qty = (Integer.parseInt( orderUpdate.getString("fillshares")));

        Double entryPrice = Double.parseDouble(orderUpdate.getString("flprc"));
        String exch = orderUpdate.getString("exch");
        String tsym = orderUpdate.getString("tsym");
        String pcode = orderUpdate.getString("pcode");


        Double slPrice = entryPrice - misc.getMaxSl(exch, token) / 2;
        slPrice = max(slPrice, 0.1);

        Double maxSlprice = entryPrice - misc.getMaxFutSl(exch, token)/2;
        maxSlprice = max(maxSlprice, 0.1);

        Double diff = misc.getTriggerdiff(exch, token );
        int minLotSize = misc.getMinLotSize(exch, token );
        List<Double> targets = misc.getTargets(exch, token );


        int div = 2;

        int multiple = qty/(div * minLotSize);
        int remaining = qty - div * minLotSize * multiple;
        PartialTrade trade;

        if(multiple > 0) {
            logger.info("qty {} is greater than or equal to 3x min_quantity {}", qty, minLotSize);

            for (int i = div; i > 0; i--) {
                String tradeName = "t" + i;
                int tradeQty = minLotSize * multiple;
                if (remaining > 0) {
                    tradeQty += minLotSize;
                    remaining -= minLotSize;
                }
                // TODO change diff per volatility

                logger.info("for {}, using qty {}", tradeName, tradeQty);
                trade = new PartialTrade(tradeName, 0, tradeQty, entryPrice,
                        slPrice, maxSlprice,entryPrice + targets.get(i - 1) , "SL-LMT",
                        pcode, exch, tsym, diff);

                this.tradeManager.addTrade(token, tradeName, trade);
            }
        }

        else if(remaining > 0){
            logger.info("qty {} less than {} x min quantity {}", qty,  div , minLotSize);
            multiple = qty / minLotSize;
            for (int j=1; j < multiple+1; j++){
                String tradeName = "t" + j;
                logger.info("for {}, using qty {}", tradeName, minLotSize);

                trade = new PartialTrade(tradeName, 0, minLotSize, entryPrice, slPrice, maxSlprice, entryPrice + targets.get(j - 1), "SL-LMT",
                        pcode, exch, tsym, diff);
                this.tradeManager.addTrade(token, tradeName, trade);
            }
        }

        subscribe(new TokenInfo(exch, token, tsym ));
    }

    public void handleBuyOrder(ShoonyaWebSocket wsClient, String token, String exch, JSONObject orderUpdate){

        if( (orderUpdate.getString("trantype").equals("B") && orderUpdate.getString("status").equals("COMPLETE")) ||
                (orderUpdate.getString("trantype").equals("B")  && orderUpdate.getString("status").equals("CANCELED") && orderUpdate.has("fillshares"))){
            if(!tradeManager.hasToken(token) ){
                this.lastbuyTime = LocalDateTime.now();
                createTrade(token, orderUpdate);
                subscribe(new TokenInfo(exch, token,null));
            }
        }
    }

    public void updateSl(String token, double newSlPrice, JSONObject orderUpdate){

        Map<String, PartialTrade> trades = tradeManager.getTrade(token);
        double oldSlPrice = trades.get("t1").getSlPrice();
        ExecutorService executor = null;

        if( oldSlPrice != newSlPrice){
            logger.debug("modifying all remaining sl from {} to {}", oldSlPrice, newSlPrice);
            try {
                executor = Executors.newFixedThreadPool(trades.size());
                for (Map.Entry<String, PartialTrade> entry : trades.entrySet()) {
                    String pt = entry.getKey();
                    PartialTrade partialTrade = entry.getValue();
                    partialTrade.setSlPrice(newSlPrice);

                    if (partialTrade.getStatus() == 1) {
                        if (partialTrade.getOrderNumber() == orderUpdate.getString("norenordno"))
                            logger.info("Sl changed manually for trade {}", partialTrade.getName());
                        else {
                            logger.info("modifying sl for {}", partialTrade.getName());
                            executor.submit(() -> shoonyaHelper.modifyOrder(partialTrade.getExch(), partialTrade.getTsym(),
                                    partialTrade.getOrderNumber(),partialTrade.getQty(), partialTrade.getOrderType(),
                                    newSlPrice, newSlPrice + partialTrade.getDiff())
                            );
                        }
                    }
                }
            }
            finally {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                        executor.shutdownNow(); // Force shutdown if not terminated
                    }            } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    public void handleSellOrder(ShoonyaWebSocket wsClient, String token, String exch, JSONObject orderUpdate){

        if( orderUpdate.getString("trantype").equals("S") && orderUpdate.getString("status").equals("TRIGGER_PENDING")){
            logger.debug("new manual sl order recieved");
            double newSlPrice = orderUpdate.getFloat("prc");
            updateSl(token, newSlPrice, orderUpdate);
        }

        if( orderUpdate.getString("trantype").equals("S") && orderUpdate.getString("status").equals("COMPLETE")){
            logger.info("selling order is {}", orderUpdate);

            Map<String, PartialTrade> trades = tradeManager.getTrade(token);

//            for(String key:trades.keySet()){
//                PartialTrade partialTrade = trades.get(key);

            for(Map.Entry<String, PartialTrade> entry: trades.entrySet()){
                String pt = entry.getKey();
                PartialTrade partialTrade = entry.getValue();
                if(partialTrade.getOrderNumber().equals(orderUpdate.getString("norenordno"))) {
                    partialTrade.setExitPrice(Double.parseDouble(orderUpdate.getString("flprc")));
                    partialTrade.setStatus(2);
                    tradeManager.updateTrade(token, pt, partialTrade);
                    logger.info("{} completed {}", pt, partialTrade);
                }
            }


            Boolean flag = true;
            for(Map.Entry<String, PartialTrade> entry: trades.entrySet()){
                PartialTrade partialTrade = entry.getValue();
                if(partialTrade.getStatus() != 2){
                    flag = false;
                    break;
                }
            }

            if(flag){
//                unsubscribe( new TokenInfo(exch, token, null));
                logger.info("all active trades for token {} completed", token);
//                logger.info("unsubscribed for token {}", token);
                boolean status = tradeManager.removeTrade(token);
                logger.debug("token {} removed from all trades with status {}", token, status);
                logger.info("All trades completed, final Trade is \n {} ",tradeManager.getTrades() );

            }
        }
    }

    public void updateOrder( ShoonyaWebSocket wsClient, @NotNull JSONObject orderUpdate) throws InterruptedException {

        String exch = orderUpdate.getString("exch");
        String tsym = orderUpdate.getString("tsym");
        String token = misc.getToken(exch, tsym);

        if (orderUpdate.has("rejreason")){
            logger.info("order rejected {}", orderUpdate);
            return;
        }

        handleBuyOrder(wsClient, token, exch, orderUpdate);
        handleSellOrder(wsClient, token, exch, orderUpdate);

        if (orderUpdate.getString("status").equals("COMPLETE")){
            riskManagementService.checkRiskManagement();
            }
    }

    public void placeSl(String pt, String token, PartialTrade trade){
        if(trade.getStatus() > 0)
            return ;
        logger.info("placing sl for a fresh order for {} ", trade);
        logger.info("placing sl for a fresh order for {} ", trade.getName());
        JSONObject res = this.shoonyaHelper.placeOrder("S", trade.getPrd(), trade.getExch(), trade.getTsym(),
                trade.getQty(), "SL-LMT", trade.getSlPrice(), trade.getSlPrice() + trade.getDiff() );

        String orderNumber = res.getString("norenordno");
        if(!res.has("resreason")) {
            trade.setOrderNumber(orderNumber);
            trade.setStatus(1);
            tradeManager.updateTrade(token, pt, trade);
        }
    }

    public void manageTrade(Double ltp, String token, String pt, PartialTrade trade){

//        logger.info("ltp for token {} is {}", token, ltp);

        Double points = ltp - trade.getEntryPrice();
        Double targetPoints = trade.getTargetPrice() - trade.getEntryPrice();
        JSONObject ret;

        if(points >= 2.0f/3 * targetPoints && trade.getOrderType().equals("SL-LMT")){
            logger.info("modifying sl order from SL-LMT to LIMIT");
            ret = this.shoonyaHelper.modifyOrder(trade.getExch(), trade.getTsym(), trade.getOrderNumber() ,trade.getQty()
                    ,"LMT", trade.getTargetPrice(), null);
            trade.setOrderType("LMT");
            logger.info("sl order modified from SL-LMT to LMT with target {}", trade.getTargetPrice());
            logger.info(ret);
        }
        if(points <= 1.0f/3 * targetPoints && trade.getOrderType().equals("LMT")){
            logger.info("modifying target order from LIMIT to SL-LMT ");

            ret = this.shoonyaHelper.modifyOrder(trade.getExch(), trade.getTsym(), trade.getOrderNumber() ,trade.getQty() ,
                    "SL-LMT", trade.getSlPrice(),trade.getSlPrice() + trade.getDiff() );
            trade.setOrderType("SL-LMT");
            logger.info("sl order modified from LIMIT to SL-LMT with sl {}", trade.getSlPrice());
            logger.info(ret);
        }
        if(ltp < trade.getMaxSlPrice()){
            logger.info("limit sl order crossed, exiting all trades with market orders");
            exitAllCurrentTrades(token);
        }
        tradeManager.updateTrade(token, pt, trade);
    }

    public void exitAllCurrentTrades(String token){

        ExecutorService executor = null;
        Map<String, PartialTrade> trades = tradeManager.getTrade(token);

        try{
            executor = Executors.newFixedThreadPool( trades.size() );
            for(Map.Entry<String, PartialTrade> entry : trades.entrySet()){
                String pt = entry.getKey();
                PartialTrade partialTrade = entry.getValue();

                executor.submit(() ->shoonyaHelper.modifyOrder(partialTrade.getExch(),
                        partialTrade.getTsym(),partialTrade.getOrderNumber(),partialTrade.getQty(),
                        "MKT", 0.0, 0.0));
            }
        }
        finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // Force shutdown if not terminated
                }            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public void manageOptionSl(String token, Double ltp ){
        if(!tradeManager.hasToken(token)){
            logger.debug("trade status false or current token is not of current trade");
            return;
        }

        Map<String, PartialTrade> trades = tradeManager.getTrade(token);
        ExecutorService executor = null;

        try {
            executor = Executors.newFixedThreadPool( trades.size() );

            for (Map.Entry<String, PartialTrade> entry : trades.entrySet()) {
                String pt = entry.getKey();
                PartialTrade partialTrade = entry.getValue();
                executor.submit(() -> placeSl(pt, token, partialTrade));
            }
        }
        finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // Force shutdown if not terminated
                }            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }



        // TODO: add in notes, entrySet is better than keySet in iterating over a map, efficient, as dont have to fetch value everytime

        trades = tradeManager.getTrade(token);

        try {
            executor = Executors.newFixedThreadPool(3);

            for (Map.Entry<String, PartialTrade> entry : trades.entrySet()) {
                String pt = entry.getKey();
                PartialTrade partialTrade = entry.getValue();
                executor.submit(() -> manageTrade(ltp, token, pt, partialTrade));
            }
        }
        finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // Force shutdown if not terminated
                }            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }



    }


    public void test(){
        PartialTrade trade = new PartialTrade("t1", 1, 100, 100.0,
                96.0, 94.0,110.0 , "LMT",
                "a", "a", "a", 0.2);
        this.tradeManager.addTrade("41692", "t1", trade);
//        manageTrade(103.0,"123", "t1", trade);
        subscribe( new TokenInfo("NFO", "41692", null));
    }

}