package com.paymentgateway.domain.valueobject;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Value;

import java.math.BigDecimal;
import java.util.Currency;

@Value
@Schema(description = "Monetary value with amount and currency")
public class Money {
    @Schema(description = "Amount as a decimal string (e.g., \"100.50\" for $100.50)", example = "100.50")
    BigDecimal amount;
    
    @Schema(description = "Currency code (ISO 4217 format, e.g., \"USD\", \"EUR\")", example = "USD")
    Currency currency;

    @JsonCreator
    public Money(
            @JsonProperty("amount") String amount,
            @JsonProperty("currency") String currency) {
        this.amount = new BigDecimal(amount);
        this.currency = Currency.getInstance(currency);
    }

    public Money(BigDecimal amount, Currency currency) {
        this.amount = amount;
        this.currency = currency;
    }

    public long getAmountCents() {
        return amount.multiply(BigDecimal.valueOf(100)).longValue();
    }

    public static Money fromCents(long cents, String currency) {
        return new Money(
                BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100)),
                Currency.getInstance(currency)
        );
    }

    public String getAmountAsString() {
        return amount.toPlainString();
    }

    public String getCurrencyCode() {
        return currency.getCurrencyCode();
    }
}

