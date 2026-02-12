package org.tripsphere.itinerary.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.tripsphere.itinerary.model.ItineraryDoc;
import org.tripsphere.itinerary.repository.ItineraryRepository;
import org.tripsphere.itinerary.service.ItineraryService.PageResult;
import org.tripsphere.itinerary.v1.Itinerary;

/**
 * Unit tests for {@link ItineraryServiceImpl}.
 *
 * <p>Uses Mockito to mock the repository layer, focusing on testing the service logic: - Page size
 * normalization - Cursor token encoding/decoding - Pagination logic (hasMore, nextPageToken)
 */
@ExtendWith(MockitoExtension.class)
class ItineraryServiceImplTest {

    @Mock private ItineraryRepository itineraryRepository;

    @InjectMocks private ItineraryServiceImpl itineraryService;

    private static final String TEST_USER_ID = "test-user-123";
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;

    @Nested
    @DisplayName("listUserItineraries - Page Size Normalization")
    class ListUserItinerariesPageSizeNormalizationTests {

        @Test
        @DisplayName("should use default page size when pageSize is 0")
        void shouldUseDefaultPageSizeWhenZero() {
            // Given
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, 0, null);

            // Then
            ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
            verify(itineraryRepository)
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            eq(TEST_USER_ID), eq(false), captor.capture());

            // Should request DEFAULT_PAGE_SIZE + 1
            assertThat(captor.getValue().getPageSize()).isEqualTo(DEFAULT_PAGE_SIZE + 1);
        }

        @Test
        @DisplayName("should use default page size when pageSize is negative")
        void shouldUseDefaultPageSizeWhenNegative() {
            // Given
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, -5, null);

            // Then
            ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
            verify(itineraryRepository)
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            eq(TEST_USER_ID), eq(false), captor.capture());

            assertThat(captor.getValue().getPageSize()).isEqualTo(DEFAULT_PAGE_SIZE + 1);
        }

        @Test
        @DisplayName("should cap page size at MAX_PAGE_SIZE")
        void shouldCapPageSizeAtMax() {
            // Given
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, 500, null);

            // Then
            ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
            verify(itineraryRepository)
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            eq(TEST_USER_ID), eq(false), captor.capture());

            assertThat(captor.getValue().getPageSize()).isEqualTo(MAX_PAGE_SIZE + 1);
        }

        @Test
        @DisplayName("should use provided page size when within valid range")
        void shouldUseProvidedPageSizeWhenValid() {
            // Given
            int requestedSize = 50;
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, requestedSize, null);

            // Then
            ArgumentCaptor<PageRequest> captor = ArgumentCaptor.forClass(PageRequest.class);
            verify(itineraryRepository)
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            eq(TEST_USER_ID), eq(false), captor.capture());

            assertThat(captor.getValue().getPageSize()).isEqualTo(requestedSize + 1);
        }
    }

    @Nested
    @DisplayName("listUserItineraries - First Page Query (No Cursor)")
    class ListUserItinerariesFirstPageQueryTests {

        @Test
        @DisplayName("should call first page query when pageToken is null")
        void shouldCallFirstPageQueryWhenTokenIsNull() {
            // Given
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, 10, null);

            // Then
            verify(itineraryRepository)
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            eq(TEST_USER_ID), eq(false), any());
            verify(itineraryRepository, never())
                    .findByUserIdWithCursor(any(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("should call first page query when pageToken is empty")
        void shouldCallFirstPageQueryWhenTokenIsEmpty() {
            // Given
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, 10, "");

            // Then
            verify(itineraryRepository)
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            eq(TEST_USER_ID), eq(false), any());
            verify(itineraryRepository, never())
                    .findByUserIdWithCursor(any(), anyBoolean(), any(), any(), any());
        }

        @Test
        @DisplayName("should return empty list with no nextPageToken when no results")
        void shouldReturnEmptyResultWhenNoData() {
            // Given
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 10, null);

            // Then
            assertThat(result.items()).isEmpty();
            assertThat(result.nextPageToken()).isNull();
        }
    }

    @Nested
    @DisplayName("listUserItineraries - Cursor-Based Query")
    class ListUserItinerariesCursorBasedQueryTests {

        @Test
        @DisplayName("should call cursor query when valid pageToken is provided")
        void shouldCallCursorQueryWhenTokenIsValid() {
            // Given: use millisecond precision since token encoding truncates to millis
            Instant cursorTime = Instant.ofEpochMilli(Instant.now().toEpochMilli());
            String cursorId = "cursor-id-123";
            String pageToken = encodeCursorToken(cursorTime, cursorId);

            when(itineraryRepository.findByUserIdWithCursor(
                            any(), anyBoolean(), any(), any(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, 10, pageToken);

            // Then
            verify(itineraryRepository, never())
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(any(), anyBoolean(), any());
            verify(itineraryRepository)
                    .findByUserIdWithCursor(
                            eq(TEST_USER_ID), eq(false), eq(cursorTime), eq(cursorId), any());
        }

        @Test
        @DisplayName("should fallback to first page query when pageToken is invalid")
        void shouldFallbackToFirstPageWhenTokenIsInvalid() {
            // Given
            String invalidToken = "not-a-valid-base64-token!!!";
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, 10, invalidToken);

            // Then
            verify(itineraryRepository)
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            eq(TEST_USER_ID), eq(false), any());
        }

        @Test
        @DisplayName("should fallback to first page when token has invalid format")
        void shouldFallbackWhenTokenFormatIsInvalid() {
            // Given: valid base64 but missing separator
            String invalidFormatToken =
                    Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString("no-separator".getBytes(StandardCharsets.UTF_8));

            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(Collections.emptyList());

            // When
            itineraryService.listUserItineraries(TEST_USER_ID, 10, invalidFormatToken);

            // Then
            verify(itineraryRepository)
                    .findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            eq(TEST_USER_ID), eq(false), any());
        }
    }

    @Nested
    @DisplayName("listUserItineraries - Pagination Logic (hasMore & nextPageToken)")
    class ListUserItinerariesPaginationLogicTests {

        @BeforeEach
        void setUp() {
            // Default: first page query
            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenAnswer(
                            invocation -> {
                                // Return empty by default, override in specific tests
                                return Collections.emptyList();
                            });
        }

        @Test
        @DisplayName("should return nextPageToken when there are more results")
        void shouldReturnNextPageTokenWhenHasMore() {
            // Given: request 2 items, return 3 (indicating hasMore)
            int pageSize = 2;
            Instant now = Instant.now();
            List<ItineraryDoc> docs = createItineraryDocs(3, now);

            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(docs);

            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, pageSize, null);

            // Then
            assertThat(result.items()).hasSize(pageSize); // Should trim to requested size
            assertThat(result.nextPageToken()).isNotNull();

            // Verify token contains last item's cursor values
            String expectedToken =
                    encodeCursorToken(docs.get(1).getCreatedAt(), docs.get(1).getId());
            assertThat(result.nextPageToken()).isEqualTo(expectedToken);
        }

        @Test
        @DisplayName("should not return nextPageToken when results equal page size")
        void shouldNotReturnNextPageTokenWhenExactPageSize() {
            // Given: request 3 items, return exactly 3 (no extra item)
            int pageSize = 3;
            List<ItineraryDoc> docs = createItineraryDocs(3, Instant.now());

            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(docs);

            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, pageSize, null);

            // Then
            assertThat(result.items()).hasSize(pageSize);
            assertThat(result.nextPageToken()).isNull();
        }

        @Test
        @DisplayName("should not return nextPageToken when results less than page size")
        void shouldNotReturnNextPageTokenWhenLessThanPageSize() {
            // Given: request 5 items, return only 2
            int pageSize = 5;
            List<ItineraryDoc> docs = createItineraryDocs(2, Instant.now());

            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(docs);

            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, pageSize, null);

            // Then
            assertThat(result.items()).hasSize(2);
            assertThat(result.nextPageToken()).isNull();
        }

        @Test
        @DisplayName("should correctly transform docs to proto objects")
        void shouldTransformDocsToProtos() {
            // Given
            List<ItineraryDoc> docs = createItineraryDocs(2, Instant.now());
            docs.get(0).setTitle("Trip to Paris");
            docs.get(1).setTitle("Tokyo Adventure");

            when(itineraryRepository.findByUserIdAndArchivedOrderByCreatedAtDescIdDesc(
                            any(), anyBoolean(), any()))
                    .thenReturn(docs);

            // When
            PageResult<Itinerary> result =
                    itineraryService.listUserItineraries(TEST_USER_ID, 10, null);

            // Then
            assertThat(result.items()).hasSize(2);
            assertThat(result.items().get(0).getTitle()).isEqualTo("Trip to Paris");
            assertThat(result.items().get(1).getTitle()).isEqualTo("Tokyo Adventure");
        }
    }

    // ==================== Helper Methods ====================

    private List<ItineraryDoc> createItineraryDocs(int count, Instant baseTime) {
        List<ItineraryDoc> docs = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ItineraryDoc doc =
                    ItineraryDoc.builder()
                            .id("id-" + i)
                            .title("Itinerary " + i)
                            .userId(TEST_USER_ID)
                            .createdAt(baseTime.minus(i, ChronoUnit.HOURS))
                            .build();
            docs.add(doc);
        }
        return docs;
    }

    /** Encodes cursor token in the same format as the service implementation. */
    private String encodeCursorToken(Instant createdAt, String id) {
        String raw = createdAt.toEpochMilli() + "|" + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
