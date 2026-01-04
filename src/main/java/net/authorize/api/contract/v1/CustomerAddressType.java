package net.authorize.api.contract.v1;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;

/**
 * Customer Address Type for Authorize.Net billing address.
 * 
 * Element order matters for XML serialization.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "customerAddressType", propOrder = {
    "firstName",
    "lastName",
    "company",
    "address",
    "city",
    "state",
    "zip",
    "country",
    "phoneNumber",
    "faxNumber"
})
public class CustomerAddressType {
    
    @XmlElement(required = false)
    private String firstName;
    
    @XmlElement(required = false)
    private String lastName;
    
    @XmlElement(required = false)
    private String company;
    
    @XmlElement(required = false)
    private String address;
    
    @XmlElement(required = false)
    private String city;
    
    @XmlElement(required = false)
    private String state;
    
    @XmlElement(required = false)
    private String zip;
    
    @XmlElement(required = false)
    private String country;
    
    @XmlElement(required = false)
    private String phoneNumber;
    
    @XmlElement(required = false)
    private String faxNumber;
    
    // Getters and setters
    public String getFirstName() {
        return firstName;
    }
    
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }
    
    public String getLastName() {
        return lastName;
    }
    
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }
    
    public String getCompany() {
        return company;
    }
    
    public void setCompany(String company) {
        this.company = company;
    }
    
    public String getAddress() {
        return address;
    }
    
    public void setAddress(String address) {
        this.address = address;
    }
    
    public String getCity() {
        return city;
    }
    
    public void setCity(String city) {
        this.city = city;
    }
    
    public String getState() {
        return state;
    }
    
    public void setState(String state) {
        this.state = state;
    }
    
    public String getZip() {
        return zip;
    }
    
    public void setZip(String zip) {
        this.zip = zip;
    }
    
    public String getCountry() {
        return country;
    }
    
    public void setCountry(String country) {
        this.country = country;
    }
    
    public String getPhoneNumber() {
        return phoneNumber;
    }
    
    public void setPhoneNumber(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }
    
    public String getFaxNumber() {
        return faxNumber;
    }
    
    public void setFaxNumber(String faxNumber) {
        this.faxNumber = faxNumber;
    }
}
