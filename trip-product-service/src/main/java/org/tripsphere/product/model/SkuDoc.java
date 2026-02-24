package org.tripsphere.product.model;

import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class SkuDoc {
    private String id;
    private String name;
    private String description;
    private String status;
    private Map<String, Object> attributes;
    private Money basePrice;
}
