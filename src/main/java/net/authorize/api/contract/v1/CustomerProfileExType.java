package net.authorize.api.contract.v1;

/**
 * Minimal compatibility shim for Authorize.Net SDK's CustomerProfileExType.
 * Implements only the methods used by AuthorizeNetGateway (setting customerProfileId and default payment profile).
 */
public class CustomerProfileExType {
    private String customerProfileId;
    private CustomerProfilePaymentType defaultPaymentProfile;

    public void setCustomerProfileId(String customerProfileId) {
        this.customerProfileId = customerProfileId;
    }

    public String getCustomerProfileId() {
        return this.customerProfileId;
    }

    public void setDefaultPaymentProfile(CustomerProfilePaymentType defaultPaymentProfile) {
        this.defaultPaymentProfile = defaultPaymentProfile;
    }

    public CustomerProfilePaymentType getDefaultPaymentProfile() {
        return this.defaultPaymentProfile;
    }
}
