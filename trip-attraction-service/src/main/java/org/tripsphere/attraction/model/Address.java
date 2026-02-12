package org.tripsphere.attraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class Address {
    private String province;
    private String city;
    private String district;
    private String detailed;
}
