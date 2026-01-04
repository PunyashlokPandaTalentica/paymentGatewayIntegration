package net.authorize.api.contract.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

/**
 * Customer Profile Payment Type for transactions.
 * Used when charging a saved customer profile.
 * 
 * CRITICAL: paymentProfile is an object wrapper, not a direct string!
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "customerProfilePaymentType", propOrder = {
    "customerProfileId",
    "paymentProfile",
    "shippingProfileId"
})
public class CustomerProfilePaymentType {
    
    @XmlElement(required = true)
    private String customerProfileId;
    
    @XmlElement(required = false)
    private PaymentProfile paymentProfile;
    
    @XmlElement(required = false)
    private String shippingProfileId;
    
    // Getters and setters
    public String getCustomerProfileId() {
        return customerProfileId;
    }
    
    public void setCustomerProfileId(String customerProfileId) {
        this.customerProfileId = customerProfileId;
    }
    
    public PaymentProfile getPaymentProfile() {
        return paymentProfile;
    }
    
    public void setPaymentProfile(PaymentProfile paymentProfile) {
        this.paymentProfile = paymentProfile;
    }
    
    /**
     * Convenience method to set payment profile ID directly
     */
    public void setPaymentProfileId(String paymentProfileId) {
        if (paymentProfileId != null && !paymentProfileId.isBlank()) {
            PaymentProfile profile = new PaymentProfile();
            profile.setPaymentProfileId(paymentProfileId);
            this.paymentProfile = profile;
        }
    }
    
    /**
     * Convenience method to get payment profile ID directly
     */
    public String getPaymentProfileId() {
        return paymentProfile != null ? paymentProfile.getPaymentProfileId() : null;
    }
    
    public String getShippingProfileId() {
        return shippingProfileId;
    }
    
    public void setShippingProfileId(String shippingProfileId) {
        this.shippingProfileId = shippingProfileId;
    }
    
    @Override
    public String toString() {
        return "CustomerProfilePaymentType{" +
                "customerProfileId='" + customerProfileId + '\'' +
                ", paymentProfile=" + paymentProfile +
                ", shippingProfileId='" + shippingProfileId + '\'' +
                '}';
    }
}
