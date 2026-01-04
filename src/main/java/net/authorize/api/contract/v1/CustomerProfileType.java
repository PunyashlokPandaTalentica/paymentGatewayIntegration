package net.authorize.api.contract.v1;

import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

/**
 * Minimal compatibility shim for Authorize.Net SDK's CustomerProfileType.
 * Exposes merchantCustomerId and payment profiles handling used by gateway adapter.
 * 
 * IMPORTANT: XmlType propOrder defines the exact order for XML serialization.
 * Authorize.Net requires elements in this specific order.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "customerProfileType", propOrder = {
    "merchantCustomerId",
    "description", 
    "email",
    "paymentProfiles"
})
public class CustomerProfileType {
    
    @XmlElement(required = false)
    private String merchantCustomerId;
    
    @XmlElement(required = false)
    private String description;
    
    @XmlElement(required = false)
    private String email;
    
    @XmlElement(required = true)
    private List<CustomerPaymentProfileType> paymentProfiles;

    // Getters and setters
    public String getMerchantCustomerId() {
        return merchantCustomerId;
    }

    public void setMerchantCustomerId(String merchantCustomerId) {
        this.merchantCustomerId = merchantCustomerId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public List<CustomerPaymentProfileType> getPaymentProfiles() {
        return paymentProfiles;
    }

    public void setPaymentProfiles(List<CustomerPaymentProfileType> paymentProfiles) {
        this.paymentProfiles = paymentProfiles;
    }
}