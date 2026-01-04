package net.authorize.api.contract.v1;

/**
 * Minimal PaymentType stub to satisfy usage in AuthorizeNetGateway.
 * Provides opaque data (Accept.js) and customer profile payment support.
 */
public class PaymentType {
    private OpaqueDataType opaqueData;
    private CustomerProfilePaymentType customerProfile;

    public void setOpaqueData(OpaqueDataType opaqueData) {
        this.opaqueData = opaqueData;
    }

    public OpaqueDataType getOpaqueData() {
        return this.opaqueData;
    }

    public void setCustomerProfile(CustomerProfilePaymentType customerProfile) {
        this.customerProfile = customerProfile;
    }

    public CustomerProfilePaymentType getCustomerProfile() {
        return this.customerProfile;
    }

    // Note: CustomerProfilePaymentType is the SDK name; our shim class is CustomerProfilePaymentType.
}
