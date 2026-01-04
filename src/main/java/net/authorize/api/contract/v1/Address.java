package net.authorize.api.contract.v1;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class Address {
    String firstName;
    String lastName;
    String company;
    String address;
    String city;
    String state;
    Integer zip;
    String country;
    String phoneNumber;
    String faxNumber;
    String email;
}
