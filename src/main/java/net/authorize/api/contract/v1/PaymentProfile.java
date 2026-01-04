package net.authorize.api.contract.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

/**
 * Payment Profile wrapper for Customer Profile transactions.
 * This is used to reference a payment profile within a customer profile transaction.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "paymentProfile", propOrder = {
    "paymentProfileId"
})
public class PaymentProfile {
    
    @XmlElement(required = true)
    private String paymentProfileId;
    
    // Getters and setters
    public String getPaymentProfileId() {
        return paymentProfileId;
    }
    
    public void setPaymentProfileId(String paymentProfileId) {
        this.paymentProfileId = paymentProfileId;
    }
    
    @Override
    public String toString() {
        return "PaymentProfile{paymentProfileId='" + paymentProfileId + "'}";
    }
}