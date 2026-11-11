package com.cexpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 交易所连接配置（exchange.*）：币安合约/现货代理、OKX 代理、以太坊 RPC，以及各自的凭证。 */
@Configuration
@ConfigurationProperties(prefix = "exchange")
public class ExchangeConfig {

    private final Binance binance = new Binance();
    private final Okx okx = new Okx();
    private final Ethereum ethereum = new Ethereum();

    public Binance getBinance() {
        return binance;
    }

    public Okx getOkx() {
        return okx;
    }

    public Ethereum getEthereum() {
        return ethereum;
    }

    /** 币安连接配置：合约与现货分别走独立代理。 */
    public static class Binance {
        /** USDⓈ-M 永续合约公共行情入口，资金费率 / OI / 标记价格都在这里。 */
        private String baseUrl = "http://43.160.213.63:18080";
        /** 现货公共行情独立代理，不能发往合约上游。 */
        private String spotBaseUrl = "http://43.160.213.63:18082";

        public String getSpotBaseUrl() { return spotBaseUrl; }
        public void setSpotBaseUrl(String spotBaseUrl) { this.spotBaseUrl = spotBaseUrl; }
        /** API Key（仅私有接口使用；当前行情接口均为公共接口，默认为空）。 */
        private String apiKey = "";
        /** 签名密钥（仅私有接口使用；当前行情接口均为公共接口，默认为空）。 */
        private String secretKey = "";

        /** @return USDⓈ-M 永续合约公共行情入口 */
        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }

    /** OKX 连接配置：现货与永续共用入口，通过产品参数区分。 */
    public static class Okx {
        /** OKX 现货与永续共用的代理入口。 */
        private String baseUrl = "http://43.160.213.63:18081";
        /** API Key / 签名密钥（仅私有接口使用；当前仅公共行情，默认为空）。 */
        private String apiKey = "";
        private String secretKey = "";
        /** OKX 私有接口要求的口令；当前仅公共行情，默认为空。 */
        private String passphrase = "";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }

        public String getPassphrase() {
            return passphrase;
        }

        public void setPassphrase(String passphrase) {
            this.passphrase = passphrase;
        }
    }

    /** 以太坊链上数据源配置。 */
    public static class Ethereum {
        /** 以太坊公共 RPC 节点地址。 */
        private String rpcUrl = "https://ethereum-rpc.publicnode.com";

        public String getRpcUrl() {
            return rpcUrl;
        }

        public void setRpcUrl(String rpcUrl) {
            this.rpcUrl = rpcUrl;
        }
    }
}
