package net.authorize.api.contract.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

/**
 * Customer Payment Profile for Authorize.Net.
 * 
 * CRITICAL: Element order must match Authorize.Net's XML schema!
 * Expected order:
 * 1. customerType (optional)
 * 2. billTo (optional) 
 * 3. payment (required)
 * 4. driversLicense (optional)
 * 5. taxId (optional)
 * 6. defaultPaymentProfile (optional)
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "customerPaymentProfileType", propOrder = {
    "customerType",
    "billTo",
    "payment",
    "defaultPaymentProfile"
})
public class CustomerPaymentProfileType {
    
    @XmlElement(required = false)
    private CustomerTypeEnum customerType;
    
    @XmlElement(required = false)
    private CustomerAddressType billTo;
    
    @XmlElement(required = true)
    private PaymentType payment;
    
    @XmlElement(required = false)
    private Boolean defaultPaymentProfile;
    
    // Getters and setters
    public CustomerTypeEnum getCustomerType() {
        return customerType;
    }
    
    public void setCustomerType(CustomerTypeEnum customerType) {
        this.customerType = customerType;
    }
    
    public CustomerAddressType getBillTo() {
        return billTo;
    }
    
    public void setBillTo(CustomerAddressType billTo) {
        this.billTo = billTo;
    }
    
    public PaymentType getPayment() {
        return payment;
    }
    
    public void setPayment(PaymentType payment) {
        this.payment = payment;
    }
    
    public Boolean getDefaultPaymentProfile() {
        return defaultPaymentProfile;
    }
    
    public void setDefaultPaymentProfile(Boolean defaultPaymentProfile) {
        this.defaultPaymentProfile = defaultPaymentProfile;
    }
}
