package org.tripsphere.attraction.mapper;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.tripsphere.attraction.domain.model.Attraction;
import org.tripsphere.attraction.infra.persistence.AttractionDoc;
import org.tripsphere.common.v1.Address;
import org.tripsphere.common.v1.GeoPoint;

class AttractionMapperTest {

    private static final AttractionMapper mapper = AttractionMapper.INSTANCE;

    // Test data constants (using Shanghai coordinates)
    private static final double GCJ02_LNG = 121.506377; // GCJ02 longitude
    private static final double GCJ02_LAT = 31.245105; // GCJ02 latitude
    private static final double WGS84_LNG = 121.501968; // WGS84 longitude
    private static final double WGS84_LAT = 31.247136; // WGS84 latitude
    private static final double COORDINATE_TOLERANCE = 0.001; // Coordinate tolerance for assertions

    @Test
    @DisplayName("Proto to Domain: should correctly map all fields")
    void protoToAttraction() {
        // Given: Create a Proto Attraction object
        org.tripsphere.attraction.v1.Attraction proto =
                org.tripsphere.attraction.v1.Attraction.newBuilder()
                        .setId("attraction_123")
                        .setName("东方明珠塔")
                        .setLocation(
                                GeoPoint.newBuilder()
                                        .setLongitude(GCJ02_LNG)
                                        .setLatitude(GCJ02_LAT)
                                        .build())
                        .setAddress(
                                Address.newBuilder()
                                        .setProvince("上海市")
                                        .setCity("上海市")
                                        .setDistrict("浦东新区")
                                        .setDetailed("世纪大道1号")
                                        .build())
                        .setIntroduction("上海地标性建筑，高468米，是亚洲第一、世界第三高塔")
                        .addAllTags(Arrays.asList("地标建筑", "观光塔", "夜景"))
                        .addAllImages(Arrays.asList("img1.jpg", "img2.jpg"))
                        .build();

        // When: Convert to Domain Attraction
        Attraction domain = mapper.protoToAttraction(proto);

        // Then: Verify all fields
        assertNotNull(domain);
        assertEquals("attraction_123", domain.getId());
        assertEquals("东方明珠塔", domain.getName());

        // Verify coordinates (GCJ02)
        assertNotNull(domain.getLocation());
        assertEquals(GCJ02_LNG, domain.getLocation().getLongitude(), COORDINATE_TOLERANCE);
        assertEquals(GCJ02_LAT, domain.getLocation().getLatitude(), COORDINATE_TOLERANCE);

        // Verify address
        assertNotNull(domain.getAddress());
        assertEquals("上海市", domain.getAddress().getProvince());
        assertEquals("上海市", domain.getAddress().getCity());
        assertEquals("浦东新区", domain.getAddress().getDistrict());
        assertEquals("世纪大道1号", domain.getAddress().getDetailed());

        // Verify other fields
        assertEquals("上海地标性建筑，高468米，是亚洲第一、世界第三高塔", domain.getIntroduction());
        assertEquals(3, domain.getTags().size());
        assertTrue(domain.getTags().contains("地标建筑"));
        assertTrue(domain.getTags().contains("观光塔"));
        assertTrue(domain.getTags().contains("夜景"));
        assertEquals(2, domain.getImages().size());
        assertTrue(domain.getImages().contains("img1.jpg"));
        assertTrue(domain.getImages().contains("img2.jpg"));

        // Timestamp fields should be ignored
        assertNull(domain.getCreatedAt());
        assertNull(domain.getUpdatedAt());
    }

    @Test
    @DisplayName("Proto to Domain: should handle null fields")
    void protoToAttractionWithNullFields() {
        // Given: Proto with only required fields
        org.tripsphere.attraction.v1.Attraction proto =
                org.tripsphere.attraction.v1.Attraction.newBuilder()
                        .setId("attraction_456")
                        .setName("测试景点")
                        .build();

        // When: Convert
        Attraction domain = mapper.protoToAttraction(proto);

        // Then: Verify
        assertNotNull(domain);
        assertEquals("attraction_456", domain.getId());
        assertEquals("测试景点", domain.getName());
        assertNull(domain.getLocation());
        assertNull(domain.getAddress());
        assertEquals("", domain.getIntroduction());
        assertNotNull(domain.getTags());
        assertTrue(domain.getTags().isEmpty());
        assertNotNull(domain.getImages());
        assertTrue(domain.getImages().isEmpty());
    }

    @Test
    @DisplayName("Proto to Domain: should handle complete data with all tags and images")
    void protoToAttractionWithCompleteData() {
        // Given: Proto with all optional fields
        org.tripsphere.attraction.v1.Attraction proto =
                org.tripsphere.attraction.v1.Attraction.newBuilder()
                        .setId("attraction_full")
                        .setName("外滩万国建筑博览群")
                        .setLocation(
                                GeoPoint.newBuilder()
                                        .setLongitude(121.490317)
                                        .setLatitude(31.240526)
                                        .build())
                        .setAddress(
                                Address.newBuilder()
                                        .setProvince("上海市")
                                        .setCity("上海市")
                                        .setDistrict("黄浦区")
                                        .setDetailed("中山东一路")
                                        .build())
                        .setIntroduction("外滩位于上海市中心黄浦区的黄浦江畔，是最具上海城市象征意义的景点之一")
                        .addAllTags(Arrays.asList("历史建筑", "夜景", "散步", "摄影", "文化"))
                        .addAllImages(
                                Arrays.asList(
                                        "bund1.jpg",
                                        "bund2.jpg",
                                        "bund3.jpg",
                                        "bund4.jpg",
                                        "bund5.jpg"))
                        .build();

        // When: Convert
        Attraction domain = mapper.protoToAttraction(proto);

        // Then: Verify
        assertNotNull(domain);
        assertEquals(5, domain.getTags().size());
        assertEquals(5, domain.getImages().size());
        assertNotNull(domain.getIntroduction());
        assertTrue(domain.getIntroduction().contains("外滩"));
    }

    @Test
    @DisplayName("Domain to Proto: should correctly map all fields")
    void attractionToProto() {
        // Given: Create a Domain Attraction object
        Attraction domain =
                Attraction.builder()
                        .id("attraction_789")
                        .name("豫园")
                        .location(new Attraction.GeoPoint(GCJ02_LNG, GCJ02_LAT))
                        .address(new Attraction.Address("上海市", "上海市", "黄浦区", "豫园老街279号"))
                        .introduction("明代园林，是上海著名的江南古典园林")
                        .tags(Arrays.asList("园林", "历史古迹", "文化"))
                        .images(Arrays.asList("yuyuan1.jpg", "yuyuan2.jpg", "yuyuan3.jpg"))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        // When: Convert to Proto
        org.tripsphere.attraction.v1.Attraction proto = mapper.attractionToProto(domain);

        // Then: Verify all fields
        assertNotNull(proto);
        assertEquals("attraction_789", proto.getId());
        assertEquals("豫园", proto.getName());

        // Verify coordinates
        assertTrue(proto.hasLocation());
        assertEquals(GCJ02_LNG, proto.getLocation().getLongitude(), COORDINATE_TOLERANCE);
        assertEquals(GCJ02_LAT, proto.getLocation().getLatitude(), COORDINATE_TOLERANCE);

        // Verify address
        assertTrue(proto.hasAddress());
        assertEquals("上海市", proto.getAddress().getProvince());
        assertEquals("上海市", proto.getAddress().getCity());
        assertEquals("黄浦区", proto.getAddress().getDistrict());
        assertEquals("豫园老街279号", proto.getAddress().getDetailed());

        // Verify other fields
        assertEquals("明代园林，是上海著名的江南古典园林", proto.getIntroduction());
        assertEquals(3, proto.getTagsCount());
        assertTrue(proto.getTagsList().contains("园林"));
        assertTrue(proto.getTagsList().contains("历史古迹"));
        assertTrue(proto.getTagsList().contains("文化"));
        assertEquals(3, proto.getImagesCount());
        assertTrue(proto.getImagesList().contains("yuyuan1.jpg"));
        assertTrue(proto.getImagesList().contains("yuyuan2.jpg"));
        assertTrue(proto.getImagesList().contains("yuyuan3.jpg"));

        // Proto does not contain timestamps, isDeleted fields
    }

    @Test
    @DisplayName("Domain to Proto: should handle null fields")
    void attractionToProtoWithNullFields() {
        // Given: Minimal Domain object
        Attraction domain = Attraction.builder().id("attraction_min").name("最小景点").build();

        // When: Convert
        org.tripsphere.attraction.v1.Attraction proto = mapper.attractionToProto(domain);

        // Then: Verify
        assertNotNull(proto);
        assertEquals("attraction_min", proto.getId());
        assertEquals("最小景点", proto.getName());
        assertFalse(proto.hasLocation());
        assertFalse(proto.hasAddress());
        assertEquals("", proto.getIntroduction());
        assertEquals(0, proto.getTagsCount());
        assertEquals(0, proto.getImagesCount());
    }

    @Test
    @DisplayName("Domain to Proto: should handle empty collections")
    void attractionToProtoWithEmptyCollections() {
        // Given: Domain with empty lists
        Attraction domain =
                Attraction.builder()
                        .id("attraction_empty")
                        .name("空集合景点")
                        .introduction("测试空集合")
                        .tags(List.of())
                        .images(List.of())
                        .build();

        // When: Convert
        org.tripsphere.attraction.v1.Attraction proto = mapper.attractionToProto(domain);

        // Then: Verify
        assertNotNull(proto);
        assertEquals(0, proto.getTagsCount());
        assertEquals(0, proto.getImagesCount());
        assertEquals("测试空集合", proto.getIntroduction());
    }

    @Test
    @DisplayName(
            "Domain to AttractionDoc: should correctly map and transform coordinates"
                    + " (GCJ02->WGS84)")
    void attractionToAttractionDoc() {
        // Given: Domain Attraction with GCJ02 coordinates
        Attraction domain =
                Attraction.builder()
                        .id("doc_123")
                        .name("上海迪士尼乐园")
                        .location(new Attraction.GeoPoint(GCJ02_LNG, GCJ02_LAT))
                        .address(new Attraction.Address("上海市", "上海市", "浦东新区", "川沙新镇黄赵路310号"))
                        .introduction("中国大陆首座迪士尼主题乐园")
                        .tags(Arrays.asList("主题乐园", "亲子", "娱乐"))
                        .images(Arrays.asList("disney1.jpg", "disney2.jpg"))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build();

        // When: Convert to AttractionDoc
        AttractionDoc doc = mapper.attractionToAttractionDoc(domain);

        // Then: Verify all fields
        assertNotNull(doc);
        assertEquals("doc_123", doc.getId());
        assertEquals("上海迪士尼乐园", doc.getName());

        // Verify coordinates transformed to WGS84
        assertNotNull(doc.getLocation());
        assertEquals(WGS84_LNG, doc.getLocation().getX(), COORDINATE_TOLERANCE);
        assertEquals(WGS84_LAT, doc.getLocation().getY(), COORDINATE_TOLERANCE);

        // Verify address
        assertNotNull(doc.getAddress());
        assertEquals("上海市", doc.getAddress().getProvince());
        assertEquals("上海市", doc.getAddress().getCity());
        assertEquals("浦东新区", doc.getAddress().getDistrict());
        assertEquals("川沙新镇黄赵路310号", doc.getAddress().getDetailed());

        // Verify other fields
        assertEquals("中国大陆首座迪士尼主题乐园", doc.getIntroduction());
        assertEquals(3, doc.getTags().size());
        assertEquals(2, doc.getImages().size());

        // isDeleted should be ignored and use default value
        assertFalse(doc.isDeleted());
    }

    @Test
    @DisplayName("Domain to AttractionDoc: should handle null location")
    void attractionToAttractionDocWithNullLocation() {
        // Given: Domain without location
        Attraction domain =
                Attraction.builder()
                        .id("doc_no_loc")
                        .name("无坐标景点")
                        .address(new Attraction.Address("上海市", "上海市", "浦东新区", "测试地址"))
                        .introduction("测试景点")
                        .build();

        // When: Convert
        AttractionDoc doc = mapper.attractionToAttractionDoc(domain);

        // Then: Verify
        assertNotNull(doc);
        assertNull(doc.getLocation());
        assertNotNull(doc.getAddress());
        assertEquals("测试景点", doc.getIntroduction());
    }

    @Test
    @DisplayName("Domain to AttractionDoc: should handle null address")
    void attractionToAttractionDocWithNullAddress() {
        // Given: Domain without address
        Attraction domain =
                Attraction.builder()
                        .id("doc_no_addr")
                        .name("无地址景点")
                        .location(new Attraction.GeoPoint(GCJ02_LNG, GCJ02_LAT))
                        .introduction("只有坐标")
                        .build();

        // When: Convert
        AttractionDoc doc = mapper.attractionToAttractionDoc(domain);

        // Then: Verify
        assertNotNull(doc);
        assertNotNull(doc.getLocation());
        assertNull(doc.getAddress());
    }

    @Test
    @DisplayName(
            "AttractionDoc to Domain: should correctly map and transform coordinates"
                    + " (WGS84->GCJ02)")
    void attractionDocToAttraction() {
        // Given: AttractionDoc with WGS84 coordinates
        AttractionDoc doc =
                AttractionDoc.builder()
                        .id("doc_456")
                        .name("田子坊")
                        .location(new GeoJsonPoint(WGS84_LNG, WGS84_LAT))
                        .address(new AttractionDoc.AddressDoc("上海市", "上海市", "黄浦区", "泰康路210弄"))
                        .introduction("上海特色的石库门建筑群改造的创意园区")
                        .tags(Arrays.asList("文创园区", "购物", "艺术"))
                        .images(List.of("tianzifang1.jpg", "tianzifang2.jpg"))
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .isDeleted(false)
                        .build();

        // When: Convert to Domain Attraction
        Attraction domain = mapper.attractionDocToAttraction(doc);

        // Then: Verify all fields
        assertNotNull(domain);
        assertEquals("doc_456", domain.getId());
        assertEquals("田子坊", domain.getName());

        // Verify coordinates transformed to GCJ02
        assertNotNull(domain.getLocation());
        assertEquals(GCJ02_LNG, domain.getLocation().getLongitude(), COORDINATE_TOLERANCE);
        assertEquals(GCJ02_LAT, domain.getLocation().getLatitude(), COORDINATE_TOLERANCE);

        // Verify address
        assertNotNull(domain.getAddress());
        assertEquals("上海市", domain.getAddress().getProvince());
        assertEquals("上海市", domain.getAddress().getCity());
        assertEquals("黄浦区", domain.getAddress().getDistrict());
        assertEquals("泰康路210弄", domain.getAddress().getDetailed());

        // Verify other fields
        assertEquals("上海特色的石库门建筑群改造的创意园区", domain.getIntroduction());
        assertEquals(3, domain.getTags().size());
        assertEquals(2, domain.getImages().size());
        assertNotNull(domain.getCreatedAt());
        assertNotNull(domain.getUpdatedAt());
    }

    @Test
    @DisplayName("AttractionDoc to Domain: should handle null location")
    void attractionDocToAttractionWithNullLocation() {
        // Given: AttractionDoc without location
        AttractionDoc doc =
                AttractionDoc.builder().id("doc_no_loc").name("无坐标文档").introduction("测试").build();

        // When: Convert
        Attraction domain = mapper.attractionDocToAttraction(doc);

        // Then: Verify
        assertNotNull(domain);
        assertEquals("doc_no_loc", domain.getId());
        assertEquals("无坐标文档", domain.getName());
        assertNull(domain.getLocation());
    }

    @Test
    @DisplayName("AttractionDoc to Domain: should handle null introduction and collections")
    void attractionDocToAttractionWithNullFields() {
        // Given: AttractionDoc with minimal data
        AttractionDoc doc =
                AttractionDoc.builder().id("doc_minimal").name("最小文档").isDeleted(false).build();

        // When: Convert
        Attraction domain = mapper.attractionDocToAttraction(doc);

        // Then: Verify
        assertNotNull(domain);
        assertEquals("doc_minimal", domain.getId());
        assertEquals("最小文档", domain.getName());
        assertNull(domain.getLocation());
        assertNull(domain.getAddress());
        assertNull(domain.getIntroduction());
        assertNull(domain.getTags());
        assertNull(domain.getImages());
    }

    @Test
    @DisplayName("Coordinate transformation: GCJ02->WGS84->GCJ02 should remain consistent")
    void coordinateTransformationRoundTrip() {
        // Given: Original GCJ02 coordinates
        Attraction.GeoPoint originalGcj02 = new Attraction.GeoPoint(GCJ02_LNG, GCJ02_LAT);

        // When: GCJ02 -> WGS84
        GeoJsonPoint wgs84 = mapper.geoPointToGeoJsonPoint(originalGcj02);

        // Then: WGS84 -> GCJ02
        Attraction.GeoPoint resultGcj02 = mapper.geoJsonPointToGeoPoint(wgs84);

        // Verify round-trip transformation maintains consistency (with small tolerance)
        assertNotNull(resultGcj02);
        assertEquals(
                originalGcj02.getLongitude(), resultGcj02.getLongitude(), COORDINATE_TOLERANCE);
        assertEquals(originalGcj02.getLatitude(), resultGcj02.getLatitude(), COORDINATE_TOLERANCE);
    }

    @Test
    @DisplayName("Coordinate transformation: should handle null values correctly")
    void coordinateTransformationWithNull() {
        // GeoPoint to GeoJsonPoint
        assertNull(mapper.geoPointToGeoJsonPoint(null));

        // GeoJsonPoint to GeoPoint
        assertNull(mapper.geoJsonPointToGeoPoint(null));
    }

    @Test
    @DisplayName("Coordinate transformation: should handle multiple coordinate pairs")
    void coordinateTransformationMultiplePoints() {
        // Test different coordinate pairs
        double[][] testCoordinates = {
            {116.397128, 39.916527}, // Beijing
            {121.473701, 31.230416}, // Shanghai
            {113.264385, 23.129110}, // Guangzhou
            {114.057868, 22.543099} // Shenzhen
        };

        for (double[] coords : testCoordinates) {
            Attraction.GeoPoint gcj02 = new Attraction.GeoPoint(coords[0], coords[1]);
            GeoJsonPoint wgs84 = mapper.geoPointToGeoJsonPoint(gcj02);
            Attraction.GeoPoint backToGcj02 = mapper.geoJsonPointToGeoPoint(wgs84);

            assertNotNull(backToGcj02);
            assertEquals(coords[0], backToGcj02.getLongitude(), COORDINATE_TOLERANCE);
            assertEquals(coords[1], backToGcj02.getLatitude(), COORDINATE_TOLERANCE);
        }
    }

    @Test
    @DisplayName("Full conversion cycle: Proto->Domain->AttractionDoc->Domain->Proto")
    void fullConversionCycle() {
        // Given: 原始 Proto 对象
        org.tripsphere.attraction.v1.Attraction originalProto =
                org.tripsphere.attraction.v1.Attraction.newBuilder()
                        .setId("cycle_test")
                        .setName("新天地")
                        .setLocation(
                                GeoPoint.newBuilder()
                                        .setLongitude(GCJ02_LNG)
                                        .setLatitude(GCJ02_LAT)
                                        .build())
                        .setAddress(
                                Address.newBuilder()
                                        .setProvince("上海市")
                                        .setCity("上海市")
                                        .setDistrict("黄浦区")
                                        .setDetailed("太仓路181弄")
                                        .build())
                        .setIntroduction("上海新天地是一个具有上海历史文化风貌的都市旅游景点")
                        .addAllTags(List.of("购物", "餐饮", "文化"))
                        .addAllImages(List.of("xintiandi1.jpg", "xintiandi2.jpg"))
                        .build();

        // When: Proto -> Domain -> AttractionDoc -> Domain -> Proto
        Attraction domain1 = mapper.protoToAttraction(originalProto);
        AttractionDoc doc = mapper.attractionToAttractionDoc(domain1);
        Attraction domain2 = mapper.attractionDocToAttraction(doc);
        org.tripsphere.attraction.v1.Attraction finalProto = mapper.attractionToProto(domain2);

        // Then: Verify key fields remain consistent
        assertEquals(originalProto.getId(), finalProto.getId());
        assertEquals(originalProto.getName(), finalProto.getName());
        assertEquals(originalProto.getIntroduction(), finalProto.getIntroduction());

        // Verify coordinates (with transformation tolerance)
        assertEquals(
                originalProto.getLocation().getLongitude(),
                finalProto.getLocation().getLongitude(),
                COORDINATE_TOLERANCE);
        assertEquals(
                originalProto.getLocation().getLatitude(),
                finalProto.getLocation().getLatitude(),
                COORDINATE_TOLERANCE);

        // Verify address
        assertEquals(
                originalProto.getAddress().getProvince(), finalProto.getAddress().getProvince());
        assertEquals(originalProto.getAddress().getCity(), finalProto.getAddress().getCity());
        assertEquals(
                originalProto.getAddress().getDistrict(), finalProto.getAddress().getDistrict());
        assertEquals(
                originalProto.getAddress().getDetailed(), finalProto.getAddress().getDetailed());

        // Verify tags and images
        assertEquals(originalProto.getTagsCount(), finalProto.getTagsCount());
        assertEquals(originalProto.getImagesCount(), finalProto.getImagesCount());
    }

    @Test
    @DisplayName("Full conversion cycle: should preserve data through all transformations")
    void fullConversionCycleWithCompleteData() {
        // Given: Complex Proto with all fields
        org.tripsphere.attraction.v1.Attraction originalProto =
                org.tripsphere.attraction.v1.Attraction.newBuilder()
                        .setId("complex_cycle")
                        .setName("上海博物馆")
                        .setLocation(
                                GeoPoint.newBuilder()
                                        .setLongitude(121.475632)
                                        .setLatitude(31.228541)
                                        .build())
                        .setAddress(
                                Address.newBuilder()
                                        .setProvince("上海市")
                                        .setCity("上海市")
                                        .setDistrict("黄浦区")
                                        .setDetailed("人民大道201号")
                                        .build())
                        .setIntroduction("上海博物馆是一座大型的中国古代艺术博物馆，馆藏文物近百万件，其中精品文物12万件")
                        .addAllTags(Arrays.asList("博物馆", "历史", "文化", "艺术", "教育"))
                        .addAllImages(
                                Arrays.asList(
                                        "museum1.jpg", "museum2.jpg", "museum3.jpg", "museum4.jpg"))
                        .build();

        // When: Complete transformation cycle
        Attraction domain1 = mapper.protoToAttraction(originalProto);
        AttractionDoc doc = mapper.attractionToAttractionDoc(domain1);
        Attraction domain2 = mapper.attractionDocToAttraction(doc);
        org.tripsphere.attraction.v1.Attraction finalProto = mapper.attractionToProto(domain2);

        // Then: Verify all fields are preserved
        assertEquals(originalProto.getId(), finalProto.getId());
        assertEquals(originalProto.getName(), finalProto.getName());
        assertEquals(originalProto.getIntroduction(), finalProto.getIntroduction());

        // Verify all tags are preserved
        assertEquals(originalProto.getTagsCount(), finalProto.getTagsCount());
        for (String tag : originalProto.getTagsList()) {
            assertTrue(finalProto.getTagsList().contains(tag), "Tag should be preserved: " + tag);
        }

        // Verify all images are preserved
        assertEquals(originalProto.getImagesCount(), finalProto.getImagesCount());
        for (String image : originalProto.getImagesList()) {
            assertTrue(
                    finalProto.getImagesList().contains(image),
                    "Image should be preserved: " + image);
        }
    }

    @Test
    @DisplayName("Mapping should handle special characters in text fields")
    void mappingWithSpecialCharacters() {
        // Given: Proto with special characters
        org.tripsphere.attraction.v1.Attraction proto =
                org.tripsphere.attraction.v1.Attraction.newBuilder()
                        .setId("special_chars_123")
                        .setName("测试景点！@#￥%……&*（）")
                        .setIntroduction("包含特殊字符：\n换行、\"引号\"、emoji 🎉")
                        .addTags("标签/分类")
                        .addTags("Tag with spaces")
                        .build();

        // When: Convert through all transformations
        Attraction domain = mapper.protoToAttraction(proto);
        org.tripsphere.attraction.v1.Attraction backToProto = mapper.attractionToProto(domain);

        // Then: Special characters should be preserved
        assertEquals(proto.getName(), backToProto.getName());
        assertEquals(proto.getIntroduction(), backToProto.getIntroduction());
        assertEquals(proto.getTagsList(), backToProto.getTagsList());
    }
}
