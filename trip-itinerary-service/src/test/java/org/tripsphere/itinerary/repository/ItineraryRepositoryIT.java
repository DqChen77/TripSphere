package org.tripsphere.itinerary.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.tripsphere.itinerary.model.ItineraryDoc;

/**
 * Integration tests for {@link ItineraryRepository}.
 *
 * <p>Uses a real MongoDB instance configured in application-test.yaml. Tests verify cursor-based
 * pagination queries work correctly.
 */
@DataMongoTest
@ActiveProfiles("test")
class ItineraryRepositoryIT {

    @Autowired private ItineraryRepository itineraryRepository;

    private static final String TEST_USER_ID = "test-user-123";
    private static final String OTHER_USER_ID = "other-user-456";

    @BeforeEach
    void setUp() {
        // Clean up before each test
        itineraryRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        itineraryRepository.deleteAll();
    }

    @Nested
    @DisplayName("findByUserIdAndArchivedOrderByCreatedAtDescIdDesc")
    class FirstPageQueryTests {

        @Test
        @DisplayName("should return empty list when no itineraries exist")
        void shouldReturnEmptyListWhenNoItineraries() {
            List<ItineraryDoc> result =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 10));

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should return itineraries ordered by createdAt DESC, id DESC")
        void shouldReturnItinerariesInCorrectOrder() {
            // Given: 3 itineraries with different createdAt times
            Instant now = Instant.now();
            createAndSaveItinerary("oldest", now.minus(2, ChronoUnit.HOURS));
            createAndSaveItinerary("middle", now.minus(1, ChronoUnit.HOURS));
            createAndSaveItinerary("newest", now);

            // When
            List<ItineraryDoc> result =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 10));

            // Then: should be ordered newest first
            assertThat(result).hasSize(3);
            assertThat(result.get(0).getTitle()).isEqualTo("newest");
            assertThat(result.get(1).getTitle()).isEqualTo("middle");
            assertThat(result.get(2).getTitle()).isEqualTo("oldest");
        }

        @Test
        @DisplayName("should respect page size limit")
        void shouldRespectPageSizeLimit() {
            // Given: 5 itineraries
            Instant now = Instant.now();
            for (int i = 0; i < 5; i++) {
                createAndSaveItinerary("itinerary-" + i, now.minus(i, ChronoUnit.HOURS));
            }

            // When: request only 3
            List<ItineraryDoc> result =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 3));

            // Then
            assertThat(result).hasSize(3);
        }

        @Test
        @DisplayName("should filter by userId")
        void shouldFilterByUserId() {
            // Given: itineraries for different users
            Instant now = Instant.now();
            createAndSaveItinerary("my-itinerary", now, TEST_USER_ID);
            createAndSaveItinerary("other-itinerary", now, OTHER_USER_ID);

            // When
            List<ItineraryDoc> result =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 10));

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("my-itinerary");
        }

        @Test
        @DisplayName("should filter out archived itineraries")
        void shouldFilterOutArchivedItineraries() {
            // Given: one active and one archived itinerary
            Instant now = Instant.now();
            createAndSaveItinerary("active", now, TEST_USER_ID, false);
            createAndSaveItinerary("archived", now.minus(1, ChronoUnit.HOURS), TEST_USER_ID, true);

            // When: query for non-archived
            List<ItineraryDoc> result =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 10));

            // Then
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTitle()).isEqualTo("active");
        }

        @Test
        @DisplayName("should handle same createdAt by ordering by id DESC")
        void shouldOrderByIdWhenCreatedAtIsSame() {
            // Given: 3 itineraries with the same createdAt
            Instant sameTime = Instant.now();
            List<ItineraryDoc> saved = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                saved.add(createAndSaveItinerary("itinerary-" + i, sameTime));
            }

            // When
            List<ItineraryDoc> result =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 10));

            // Then: should be ordered by id DESC (MongoDB ObjectId is roughly time-ordered)
            assertThat(result).hasSize(3);
            // Verify ordering is consistent (ids should be in descending order)
            for (int i = 0; i < result.size() - 1; i++) {
                assertThat(result.get(i).getId()).isGreaterThan(result.get(i + 1).getId());
            }
        }
    }

    @Nested
    @DisplayName("findByUserIdWithCursor")
    class CursorPaginationTests {

        @Test
        @DisplayName("should return records after cursor position")
        void shouldReturnRecordsAfterCursor() {
            // Given: 5 itineraries
            Instant now = Instant.now();
            List<ItineraryDoc> allDocs = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                allDocs.add(
                        createAndSaveItinerary("itinerary-" + i, now.minus(i, ChronoUnit.HOURS)));
            }

            // Get the first page to establish cursor
            List<ItineraryDoc> firstPage =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 2));

            assertThat(firstPage).hasSize(2);

            // Use last item of first page as cursor
            ItineraryDoc lastOfFirstPage = firstPage.get(1);

            // When: query with cursor
            List<ItineraryDoc> secondPage =
                    itineraryRepository.findByUserIdWithCursor(
                            TEST_USER_ID,
                            false,
                            lastOfFirstPage.getCreatedAt(),
                            lastOfFirstPage.getId(),
                            PageRequest.of(0, 2));

            // Then: should get the next 2 items
            assertThat(secondPage).hasSize(2);
            // Verify no overlap with first page
            assertThat(secondPage.get(0).getId()).isNotEqualTo(firstPage.get(0).getId());
            assertThat(secondPage.get(0).getId()).isNotEqualTo(firstPage.get(1).getId());
            // Verify correct order (should continue from where first page ended)
            assertThat(secondPage.get(0).getCreatedAt())
                    .isBeforeOrEqualTo(lastOfFirstPage.getCreatedAt());
        }

        @Test
        @DisplayName("should handle cursor at same createdAt correctly")
        void shouldHandleSameCreatedAtWithCursor() {
            // Given: 5 itineraries with the same createdAt (edge case)
            Instant sameTime = Instant.now();
            List<ItineraryDoc> saved = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                saved.add(createAndSaveItinerary("itinerary-" + i, sameTime));
            }

            // Get first page
            List<ItineraryDoc> firstPage =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 2));

            ItineraryDoc cursor = firstPage.get(1);

            // When: query with cursor
            List<ItineraryDoc> secondPage =
                    itineraryRepository.findByUserIdWithCursor(
                            TEST_USER_ID,
                            false,
                            cursor.getCreatedAt(),
                            cursor.getId(),
                            PageRequest.of(0, 2));

            // Then: should get next 2 items, no duplicates
            assertThat(secondPage).hasSize(2);
            // Verify ids are smaller than cursor (since same createdAt, ordered by id DESC)
            for (ItineraryDoc doc : secondPage) {
                assertThat(doc.getId()).isLessThan(cursor.getId());
            }
        }

        @Test
        @DisplayName("should return empty list when cursor is at the end")
        void shouldReturnEmptyWhenCursorAtEnd() {
            // Given: 2 itineraries
            Instant now = Instant.now();
            createAndSaveItinerary("first", now);
            createAndSaveItinerary("last", now.minus(1, ChronoUnit.HOURS));

            // Get all items
            List<ItineraryDoc> allItems =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 10));

            // Use last item as cursor
            ItineraryDoc lastItem = allItems.get(allItems.size() - 1);

            // When: query with cursor pointing to the last item
            List<ItineraryDoc> result =
                    itineraryRepository.findByUserIdWithCursor(
                            TEST_USER_ID,
                            false,
                            lastItem.getCreatedAt(),
                            lastItem.getId(),
                            PageRequest.of(0, 10));

            // Then: should be empty
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should filter by userId with cursor")
        void shouldFilterByUserIdWithCursor() {
            // Given: itineraries for different users
            Instant now = Instant.now();
            createAndSaveItinerary("my-1", now, TEST_USER_ID);
            createAndSaveItinerary("my-2", now.minus(1, ChronoUnit.HOURS), TEST_USER_ID);
            createAndSaveItinerary("other-1", now.minus(30, ChronoUnit.MINUTES), OTHER_USER_ID);

            // Get first page for TEST_USER_ID
            List<ItineraryDoc> firstPage =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, 1));

            ItineraryDoc cursor = firstPage.get(0);

            // When
            List<ItineraryDoc> result =
                    itineraryRepository.findByUserIdWithCursor(
                            TEST_USER_ID,
                            false,
                            cursor.getCreatedAt(),
                            cursor.getId(),
                            PageRequest.of(0, 10));

            // Then: should only contain TEST_USER_ID's itineraries
            assertThat(result).hasSize(1);
            assertThat(result.get(0).getUserId()).isEqualTo(TEST_USER_ID);
        }

        @Test
        @DisplayName("should paginate through all items without duplicates or gaps")
        void shouldPaginateThroughAllItemsCorrectly() {
            // Given: 10 itineraries
            Instant now = Instant.now();
            for (int i = 0; i < 10; i++) {
                createAndSaveItinerary("itinerary-" + i, now.minus(i, ChronoUnit.MINUTES));
            }

            // When: paginate through all items with page size 3
            List<String> collectedIds = new ArrayList<>();
            int pageSize = 3;

            // First page
            List<ItineraryDoc> page =
                    itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            TEST_USER_ID, false, PageRequest.of(0, pageSize));

            while (!page.isEmpty()) {
                for (ItineraryDoc doc : page) {
                    collectedIds.add(doc.getId());
                }

                if (page.size() < pageSize) {
                    break; // No more pages
                }

                // Use last item as cursor for next page
                ItineraryDoc cursor = page.get(page.size() - 1);
                page =
                        itineraryRepository.findByUserIdWithCursor(
                                TEST_USER_ID,
                                false,
                                cursor.getCreatedAt(),
                                cursor.getId(),
                                PageRequest.of(0, pageSize));
            }

            // Then: should have collected all 10 unique ids
            assertThat(collectedIds).hasSize(10);
            assertThat(collectedIds).doesNotHaveDuplicates();
        }
    }

    // ==================== Helper Methods ====================

    private ItineraryDoc createAndSaveItinerary(String title, Instant createdAt) {
        return createAndSaveItinerary(title, createdAt, TEST_USER_ID, false);
    }

    private ItineraryDoc createAndSaveItinerary(String title, Instant createdAt, String userId) {
        return createAndSaveItinerary(title, createdAt, userId, false);
    }

    private ItineraryDoc createAndSaveItinerary(
            String title, Instant createdAt, String userId, boolean archived) {
        ItineraryDoc doc =
                ItineraryDoc.builder().title(title).userId(userId).archived(archived).build();

        // Save first to generate id, then update createdAt
        ItineraryDoc saved = itineraryRepository.save(doc);

        // Manually set createdAt for test control (since @CreatedDate sets it automatically)
        saved.setCreatedAt(createdAt);
        return itineraryRepository.save(saved);
    }
}
