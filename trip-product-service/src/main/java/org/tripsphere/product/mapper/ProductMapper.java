package org.tripsphere.product.mapper;

import com.google.protobuf.ListValue;
import com.google.protobuf.NullValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mapstruct.*;
import org.mapstruct.factory.Mappers;
import org.tripsphere.product.model.Money;
import org.tripsphere.product.model.SkuDoc;
import org.tripsphere.product.model.SpuDoc;
import org.tripsphere.product.v1.ResourceType;
import org.tripsphere.product.v1.SkuStatus;
import org.tripsphere.product.v1.SpuStatus;
import org.tripsphere.product.v1.StandardProductUnit;
import org.tripsphere.product.v1.StockKeepingUnit;

@Mapper(
        collectionMappingStrategy = CollectionMappingStrategy.ADDER_PREFERRED,
        unmappedTargetPolicy = ReportingPolicy.IGNORE,
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS)
public interface ProductMapper {
    ProductMapper INSTANCE = Mappers.getMapper(ProductMapper.class);

    default SpuDoc toDoc(StandardProductUnit proto) {
        if (proto == null) return null;
        SpuDoc doc = new SpuDoc();
        doc.setId(proto.getId().isEmpty() ? null : proto.getId());
        doc.setName(proto.getName());
        doc.setDescription(proto.getDescription());
        doc.setResourceType(resourceTypeToString(proto.getResourceType()));
        doc.setResourceId(proto.getResourceId());
        doc.setImages(
                proto.getImagesList().isEmpty() ? null : new ArrayList<>(proto.getImagesList()));
        doc.setStatus(spuStatusToString(proto.getStatus()));
        doc.setAttributes(proto.hasAttributes() ? structToMap(proto.getAttributes()) : null);
        doc.setSkus(proto.getSkusList().isEmpty() ? null : toSkuDocList(proto.getSkusList()));
        return doc;
    }

    default StandardProductUnit toProto(SpuDoc doc) {
        if (doc == null) return null;
        StandardProductUnit.Builder builder = StandardProductUnit.newBuilder();
        if (doc.getId() != null) builder.setId(doc.getId());
        if (doc.getName() != null) builder.setName(doc.getName());
        if (doc.getDescription() != null) builder.setDescription(doc.getDescription());
        builder.setResourceType(stringToResourceType(doc.getResourceType()));
        if (doc.getResourceId() != null) builder.setResourceId(doc.getResourceId());
        if (doc.getImages() != null) builder.addAllImages(doc.getImages());
        builder.setStatus(stringToSpuStatus(doc.getStatus()));
        if (doc.getAttributes() != null) builder.setAttributes(mapToStruct(doc.getAttributes()));
        if (doc.getSkus() != null) {
            for (SkuDoc skuDoc : doc.getSkus()) {
                builder.addSkus(toSkuProto(skuDoc, doc.getId()));
            }
        }
        return builder.build();
    }

    default List<StandardProductUnit> toProtoList(List<SpuDoc> docs) {
        if (docs == null) return List.of();
        return docs.stream().map(this::toProto).toList();
    }

    default SkuDoc toSkuDoc(StockKeepingUnit proto) {
        if (proto == null) return null;
        SkuDoc doc = new SkuDoc();
        doc.setId(proto.getId().isEmpty() ? UUID.randomUUID().toString() : proto.getId());
        doc.setName(proto.getName());
        doc.setDescription(proto.getDescription());
        doc.setStatus(skuStatusToString(proto.getStatus()));
        doc.setAttributes(proto.hasAttributes() ? structToMap(proto.getAttributes()) : null);
        doc.setBasePrice(toMoney(proto.getBasePrice()));
        return doc;
    }

    default List<SkuDoc> toSkuDocList(List<StockKeepingUnit> protos) {
        if (protos == null) return List.of();
        return protos.stream().map(this::toSkuDoc).toList();
    }

    default StockKeepingUnit toSkuProto(SkuDoc doc, String spuId) {
        if (doc == null) return null;
        StockKeepingUnit.Builder builder = StockKeepingUnit.newBuilder();
        if (doc.getId() != null) builder.setId(doc.getId());
        if (doc.getName() != null) builder.setName(doc.getName());
        if (spuId != null) builder.setSpuId(spuId);
        if (doc.getDescription() != null) builder.setDescription(doc.getDescription());
        builder.setStatus(stringToSkuStatus(doc.getStatus()));
        if (doc.getAttributes() != null) builder.setAttributes(mapToStruct(doc.getAttributes()));
        if (doc.getBasePrice() != null) builder.setBasePrice(toMoneyProto(doc.getBasePrice()));
        return builder.build();
    }

    default Money toMoney(org.tripsphere.common.v1.Money proto) {
        if (proto == null || proto.equals(org.tripsphere.common.v1.Money.getDefaultInstance())) {
            return null;
        }
        Currency currency = Currency.getInstance(proto.getCurrency());
        BigDecimal amount =
                BigDecimal.valueOf(proto.getUnits()).add(BigDecimal.valueOf(proto.getNanos(), 9));
        return new Money(currency, amount);
    }

    default org.tripsphere.common.v1.Money toMoneyProto(Money doc) {
        if (doc == null) return org.tripsphere.common.v1.Money.getDefaultInstance();
        org.tripsphere.common.v1.Money.Builder builder =
                org.tripsphere.common.v1.Money.newBuilder();
        if (doc.currency() != null) {
            builder.setCurrency(doc.currency().getCurrencyCode());
        }
        if (doc.amount() != null) {
            long units = doc.amount().longValue();
            int nanos = doc.amount().remainder(BigDecimal.ONE).movePointRight(9).intValue();
            builder.setUnits(units);
            builder.setNanos(nanos);
        }
        return builder.build();
    }

    default Map<String, Object> structToMap(Struct struct) {
        if (struct == null) return null;
        Map<String, Object> map = new HashMap<>();
        for (Map.Entry<String, Value> entry : struct.getFieldsMap().entrySet()) {
            map.put(entry.getKey(), valueToObject(entry.getValue()));
        }
        return map;
    }

    default Object valueToObject(Value value) {
        if (value == null) return null;
        return switch (value.getKindCase()) {
            case NULL_VALUE -> null;
            case NUMBER_VALUE -> value.getNumberValue();
            case STRING_VALUE -> value.getStringValue();
            case BOOL_VALUE -> value.getBoolValue();
            case STRUCT_VALUE -> structToMap(value.getStructValue());
            case LIST_VALUE -> value.getListValue().getValuesList().stream()
                    .map(this::valueToObject)
                    .toList();
            default -> null;
        };
    }

    default Struct mapToStruct(Map<String, Object> map) {
        if (map == null) return null;
        Struct.Builder builder = Struct.newBuilder();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            builder.putFields(entry.getKey(), objectToValue(entry.getValue()));
        }
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    default Value objectToValue(Object obj) {
        if (obj == null) {
            return Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        }
        if (obj instanceof Number number) {
            return Value.newBuilder().setNumberValue(number.doubleValue()).build();
        }
        if (obj instanceof String str) {
            return Value.newBuilder().setStringValue(str).build();
        }
        if (obj instanceof Boolean bool) {
            return Value.newBuilder().setBoolValue(bool).build();
        }
        if (obj instanceof Map<?, ?> map) {
            return Value.newBuilder()
                    .setStructValue(mapToStruct((Map<String, Object>) map))
                    .build();
        }
        if (obj instanceof List<?> list) {
            ListValue.Builder listBuilder = ListValue.newBuilder();
            for (Object item : list) {
                listBuilder.addValues(objectToValue(item));
            }
            return Value.newBuilder().setListValue(listBuilder.build()).build();
        }
        return Value.newBuilder().setStringValue(obj.toString()).build();
    }

    default String resourceTypeToString(ResourceType type) {
        return switch (type) {
            case RESOURCE_TYPE_HOTEL_ROOM -> "HOTEL_ROOM";
            case RESOURCE_TYPE_ATTRACTION -> "ATTRACTION";
            default -> "UNSPECIFIED";
        };
    }

    default ResourceType stringToResourceType(String type) {
        if (type == null) return ResourceType.RESOURCE_TYPE_UNSPECIFIED;
        return switch (type) {
            case "HOTEL_ROOM" -> ResourceType.RESOURCE_TYPE_HOTEL_ROOM;
            case "ATTRACTION" -> ResourceType.RESOURCE_TYPE_ATTRACTION;
            default -> ResourceType.RESOURCE_TYPE_UNSPECIFIED;
        };
    }

    default String spuStatusToString(SpuStatus status) {
        return switch (status) {
            case SPU_STATUS_DRAFT -> "DRAFT";
            case SPU_STATUS_ON_SHELF -> "ON_SHELF";
            case SPU_STATUS_OFF_SHELF -> "OFF_SHELF";
            case SPU_STATUS_DELETED -> "DELETED";
            default -> "DRAFT";
        };
    }

    default SpuStatus stringToSpuStatus(String status) {
        if (status == null) return SpuStatus.SPU_STATUS_UNSPECIFIED;
        return switch (status) {
            case "DRAFT" -> SpuStatus.SPU_STATUS_DRAFT;
            case "ON_SHELF" -> SpuStatus.SPU_STATUS_ON_SHELF;
            case "OFF_SHELF" -> SpuStatus.SPU_STATUS_OFF_SHELF;
            case "DELETED" -> SpuStatus.SPU_STATUS_DELETED;
            default -> SpuStatus.SPU_STATUS_UNSPECIFIED;
        };
    }

    default String skuStatusToString(SkuStatus status) {
        return switch (status) {
            case SKU_STATUS_ACTIVE -> "ACTIVE";
            case SKU_STATUS_INACTIVE -> "INACTIVE";
            case SKU_STATUS_DELETED -> "DELETED";
            default -> "ACTIVE";
        };
    }

    default SkuStatus stringToSkuStatus(String status) {
        if (status == null) return SkuStatus.SKU_STATUS_UNSPECIFIED;
        return switch (status) {
            case "ACTIVE" -> SkuStatus.SKU_STATUS_ACTIVE;
            case "INACTIVE" -> SkuStatus.SKU_STATUS_INACTIVE;
            case "DELETED" -> SkuStatus.SKU_STATUS_DELETED;
            default -> SkuStatus.SKU_STATUS_UNSPECIFIED;
        };
    }
}
