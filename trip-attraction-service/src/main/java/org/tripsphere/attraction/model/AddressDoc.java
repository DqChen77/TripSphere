package org.tripsphere.attraction.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.mongodb.core.index.Indexed;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AddressDoc {
    private String province;
    private String city;
    @Indexed private String district;
    private String detailed;
}
