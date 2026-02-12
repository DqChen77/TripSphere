package org.tripsphere.itinerary.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.tripsphere.itinerary.model.ItineraryDoc;
import org.tripsphere.itinerary.repository.ItineraryRepository;
import org.tripsphere.itinerary.service.ItineraryService;
import org.tripsphere.itinerary.service.ItineraryService.PageResult;
import org.tripsphere.itinerary.v1.Itinerary;

/**
 * Integration tests for {@link ItineraryServiceImpl}.
 *
 * <p>Uses a real MongoDB instance to test the complete flow from service layer to database. Tests
 * verify the cursor-based pagination works correctly end-to-end.
 */
@SpringBootTest
@ActiveProfiles("test")
class ItineraryServiceImplIT {

    @Autowired private ItineraryService itineraryService;

    @Autowired private ItineraryRepository itineraryRepository;

    private static final String TEST_USER_ID = "integration-test-user";
    private static final String OTHER_USER_ID = "other-user";

    @BeforeEach
    void setUp() {
        itineraryRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        itineraryRepository.deleteAll();
    }

    @Nested
    @DisplayName("listUserItineraries - Basic Functionality")
    class ListUserItinerariesBasicFunctionalityTests {

        @Test
        @DisplayName("should return empty result when user has no itineraries")
        void shouldReturnEmptyWhenNoItineraries() {
            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 10, null);

            // Then
            assertThat(result.items()).isEmpty();
            assertThat(result.nextPageToken()).isNull();
        }

        @Test
        @DisplayName("should return user's itineraries ordered by createdAt DESC")
        void shouldReturnItinerariesOrderedByCreatedAtDesc() {
            // Given: create 3 itineraries at different times
            Instant now = Instant.now();
            createItinerary("Oldest Trip", now.minus(2, ChronoUnit.DAYS));
            createItinerary("Middle Trip", now.minus(1, ChronoUnit.DAYS));
            createItinerary("Newest Trip", now);

            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 10, null);

            // Then
            assertThat(result.items()).hasSize(3);
            assertThat(result.items().get(0).getTitle()).isEqualTo("Newest Trip");
            assertThat(result.items().get(1).getTitle()).isEqualTo("Middle Trip");
            assertThat(result.items().get(2).getTitle()).isEqualTo("Oldest Trip");
        }

        @Test
        @DisplayName("should only return current user's itineraries")
        void shouldOnlyReturnCurrentUserItineraries() {
            // Given
            Instant now = Instant.now();
            createItinerary("My Trip 1", now, TEST_USER_ID);
            createItinerary("My Trip 2", now.minus(1, ChronoUnit.HOURS), TEST_USER_ID);
            createItinerary("Other User Trip", now, OTHER_USER_ID);

            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 10, null);

            // Then
            assertThat(result.items()).hasSize(2);
            assertThat(result.items()).allMatch(it -> it.getUserId().equals(TEST_USER_ID));
        }

        @Test
        @DisplayName("should exclude archived itineraries")
        void shouldExcludeArchivedItineraries() {
            // Given
            Instant now = Instant.now();
            createItinerary("Active Trip", now, TEST_USER_ID, false);
            createItinerary("Archived Trip", now.minus(1, ChronoUnit.HOURS), TEST_USER_ID, true);

            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 10, null);

            // Then
            assertThat(result.items()).hasSize(1);
            assertThat(result.items().get(0).getTitle()).isEqualTo("Active Trip");
        }
    }

    @Nested
    @DisplayName("listUserItineraries - Pagination")
    class ListUserItinerariesPaginationTests {

        @Test
        @DisplayName("should respect page size limit")
        void shouldRespectPageSizeLimit() {
            // Given: create 5 itineraries
            Instant now = Instant.now();
            for (int i = 0; i < 5; i++) {
                createItinerary("Trip " + i, now.minus(i, ChronoUnit.HOURS));
            }

            // When: request only 3
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 3, null);

            // Then
            assertThat(result.items()).hasSize(3);
            assertThat(result.nextPageToken()).isNotNull();
        }

        @Test
        @DisplayName("should return nextPageToken when more results exist")
        void shouldReturnNextPageTokenWhenMoreExist() {
            // Given: create 5 itineraries
            Instant now = Instant.now();
            for (int i = 0; i < 5; i++) {
                createItinerary("Trip " + i, now.minus(i, ChronoUnit.HOURS));
            }

            // When: request 3
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 3, null);

            // Then
            assertThat(result.nextPageToken()).isNotNull();
        }

        @Test
        @DisplayName("should not return nextPageToken when no more results")
        void shouldNotReturnNextPageTokenWhenNoMore() {
            // Given: create 3 itineraries
            Instant now = Instant.now();
            for (int i = 0; i < 3; i++) {
                createItinerary("Trip " + i, now.minus(i, ChronoUnit.HOURS));
            }

            // When: request 5 (more than available)
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 5, null);

            // Then
            assertThat(result.items()).hasSize(3);
            assertThat(result.nextPageToken()).isNull();
        }

        @Test
        @DisplayName("should fetch next page using nextPageToken")
        void shouldFetchNextPageUsingToken() {
            // Given: create 5 itineraries
            Instant now = Instant.now();
            for (int i = 0; i < 5; i++) {
                createItinerary("Trip " + i, now.minus(i, ChronoUnit.HOURS));
            }

            // When: get first page
            PageResult<Itinerary> firstPage =
                    itineraryService.listUserItineraries(TEST_USER_ID, 3, null);

            // Then: get second page using token
            PageResult<Itinerary> secondPage =
                    itineraryService.listUserItineraries(
                            TEST_USER_ID, 3, firstPage.nextPageToken());

            assertThat(secondPage.items()).hasSize(2);
            assertThat(secondPage.nextPageToken()).isNull(); // No more pages

            // Verify no overlap
            Set<String> firstPageIds = new HashSet<>();
            for (Itinerary it : firstPage.items()) {
                firstPageIds.add(it.getId());
            }
            for (Itinerary it : secondPage.items()) {
                assertThat(firstPageIds).doesNotContain(it.getId());
            }
        }

        @Test
        @DisplayName("should paginate through all items without duplicates or gaps")
        void shouldPaginateThroughAllItemsCorrectly() {
            // Given: create 10 itineraries
            Instant now = Instant.now();
            List<String> expectedTitles = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                String title = "Trip " + i;
                createItinerary(title, now.minus(i, ChronoUnit.MINUTES));
                expectedTitles.add(title);
            }

            // When: paginate through all with page size 3
            List<String> collectedTitles = new ArrayList<>();
            String pageToken = null;

            do {
                PageResult<Itinerary> page =
                        itineraryService.listUserItineraries(TEST_USER_ID, 3, pageToken);

                for (Itinerary it : page.items()) {
                    collectedTitles.add(it.getTitle());
                }

                pageToken = page.nextPageToken();
            } while (pageToken != null);

            // Then: should have all 10 unique items
            assertThat(collectedTitles).hasSize(10);
            assertThat(collectedTitles).doesNotHaveDuplicates();
            assertThat(collectedTitles).containsExactlyElementsOf(expectedTitles);
        }
    }

    @Nested
    @DisplayName("listUserItineraries - Edge Cases")
    class ListUserItinerariesEdgeCaseTests {

        @Test
        @DisplayName("should handle items with same createdAt correctly")
        void shouldHandleSameCreatedAtCorrectly() {
            // Given: create 5 itineraries at the exact same time
            Instant sameTime = Instant.now();
            for (int i = 0; i < 5; i++) {
                createItinerary("Same Time Trip " + i, sameTime);
            }

            // When: paginate through all
            List<String> collectedIds = new ArrayList<>();
            String pageToken = null;

            do {
                PageResult<Itinerary> page =
                        itineraryService.listUserItineraries(TEST_USER_ID, 2, pageToken);

                for (Itinerary it : page.items()) {
                    collectedIds.add(it.getId());
                }

                pageToken = page.nextPageToken();
            } while (pageToken != null);

            // Then: should have all 5 unique items, no duplicates
            assertThat(collectedIds).hasSize(5);
            assertThat(collectedIds).doesNotHaveDuplicates();
        }

        @Test
        @DisplayName("should handle invalid pageToken gracefully")
        void shouldHandleInvalidTokenGracefully() {
            // Given: create some itineraries
            Instant now = Instant.now();
            createItinerary("Trip 1", now);
            createItinerary("Trip 2", now.minus(1, ChronoUnit.HOURS));

            // When: use invalid token
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 10, "invalid-token!!!");

            // Then: should return first page (graceful fallback)
            assertThat(result.items()).hasSize(2);
        }

        @Test
        @DisplayName("should use default page size when 0 is provided")
        void shouldUseDefaultPageSizeWhenZero() {
            // Given: create 25 itineraries (more than default page size of 20)
            Instant now = Instant.now();
            for (int i = 0; i < 25; i++) {
                createItinerary("Trip " + i, now.minus(i, ChronoUnit.MINUTES));
            }

            // When: request with page size 0
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 0, null);

            // Then: should use default (20) and have nextPageToken
            assertThat(result.items()).hasSize(20);
            assertThat(result.nextPageToken()).isNotNull();
        }

        @Test
        @DisplayName("should cap page size at maximum")
        void shouldCapPageSizeAtMaximum() {
            // Given: create 110 itineraries (more than max of 100)
            Instant now = Instant.now();
            for (int i = 0; i < 110; i++) {
                createItinerary("Trip " + i, now.minus(i, ChronoUnit.SECONDS));
            }

            // When: request with very large page size
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 500, null);

            // Then: should cap at 100
            assertThat(result.items()).hasSize(100);
            assertThat(result.nextPageToken()).isNotNull();
        }
    }

    // ==================== Helper Methods ====================

    private ItineraryDoc createItinerary(String title, Instant createdAt) {
        return createItinerary(title, createdAt, TEST_USER_ID, false);
    }

    private ItineraryDoc createItinerary(String title, Instant createdAt, String userId) {
        return createItinerary(title, createdAt, userId, false);
    }

    private ItineraryDoc createItinerary(
            String title, Instant createdAt, String userId, boolean archived) {
        ItineraryDoc doc =
                ItineraryDoc.builder().title(title).userId(userId).archived(archived).build();

        ItineraryDoc saved = itineraryRepository.save(doc);

        // Manually set createdAt for test control
        saved.setCreatedAt(createdAt);
        return itineraryRepository.save(saved);
    }
}
