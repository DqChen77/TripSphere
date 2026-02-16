package repository

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"

	"trip-review-service/internal/domain"
)

// ============================================================
// Cursor Token Encoding/Decoding Tests
// ============================================================

func TestEncodeCursorToken(t *testing.T) {
	tests := []struct {
		name      string
		timestamp time.Time
		id        string
	}{
		{
			name:      "basic encoding",
			timestamp: time.UnixMilli(1704067200000),
			id:        "review-123",
		},
		{
			name:      "with special characters in id",
			timestamp: time.UnixMilli(1704067200000),
			id:        "review-abc-def-123",
		},
		{
			name:      "zero timestamp",
			timestamp: time.UnixMilli(0),
			id:        "review-0",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			token := encodeCursorToken(tt.timestamp, tt.id)
			assert.NotEmpty(t, token)

			// Decode and verify
			decodedTime, decodedID, err := decodeCursorToken(token)
			assert.NoError(t, err)
			assert.Equal(t, tt.timestamp.UnixMilli(), decodedTime.UnixMilli())
			assert.Equal(t, tt.id, decodedID)
		})
	}
}

func TestDecodeCursorToken(t *testing.T) {
	tests := []struct {
		name          string
		token         string
		expectedTime  int64
		expectedID    string
		expectedError bool
	}{
		{
			name:          "valid token",
			token:         encodeCursorToken(time.UnixMilli(1704067200000), "review-123"),
			expectedTime:  1704067200000,
			expectedID:    "review-123",
			expectedError: false,
		},
		{
			name:          "invalid base64",
			token:         "not-valid-base64!!!",
			expectedError: true,
		},
		{
			name:          "missing separator",
			token:         "MTcwNDA2NzIwMDAwMA", // "1704067200000" without separator and id
			expectedError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			decodedTime, decodedID, err := decodeCursorToken(tt.token)

			if tt.expectedError {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
				assert.Equal(t, tt.expectedTime, decodedTime.UnixMilli())
				assert.Equal(t, tt.expectedID, decodedID)
			}
		})
	}
}

// ============================================================
// ParseSortOptions Tests
// ============================================================

func TestParseSortOptions(t *testing.T) {
	tests := []struct {
		name          string
		orderBy       string
		expectedField string
		expectedOrder int
	}{
		{
			name:          "empty order_by defaults to updated_at desc",
			orderBy:       "",
			expectedField: "updated_at",
			expectedOrder: -1,
		},
		{
			name:          "created_at ascending",
			orderBy:       "created_at",
			expectedField: "created_at",
			expectedOrder: 1,
		},
		{
			name:          "created_at descending",
			orderBy:       "created_at desc",
			expectedField: "created_at",
			expectedOrder: -1,
		},
		{
			name:          "updated_at ascending",
			orderBy:       "updated_at",
			expectedField: "updated_at",
			expectedOrder: 1,
		},
		{
			name:          "updated_at descending",
			orderBy:       "updated_at desc",
			expectedField: "updated_at",
			expectedOrder: -1,
		},
		{
			name:          "invalid field defaults to updated_at desc",
			orderBy:       "invalid_field",
			expectedField: "updated_at",
			expectedOrder: -1,
		},
		{
			name:          "case insensitive DESC",
			orderBy:       "created_at DESC",
			expectedField: "created_at",
			expectedOrder: -1,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			field, order := parseSortOptions(tt.orderBy)
			assert.Equal(t, tt.expectedField, field)
			assert.Equal(t, tt.expectedOrder, order)
		})
	}
}

// ============================================================
// Test Helpers
// ============================================================

func newTestReview() *domain.Review {
	now := time.Now()
	return &domain.Review{
		ID:         "review-123",
		UserID:     "user-456",
		EntityType: domain.EntityTypeHotel,
		EntityID:   "hotel-789",
		Rating:     8,
		Content:    "Great hotel!",
		Images:     []string{"img1.jpg", "img2.jpg"},
		Dimensions: map[string]uint32{"view": 4, "service": 5},
		CreatedAt:  now,
		UpdatedAt:  now,
	}
}

// ============================================================
// Interface Compliance Test
// ============================================================

func TestReviewRepo_ImplementsInterface(t *testing.T) {
	// This is a compile-time check to ensure ReviewRepo implements the interface
	// We can't actually create a repo without a MongoDB connection,
	// but the compiler will verify the interface compliance
	var _ domain.ReviewRepository = (*ReviewRepo)(nil)
}

// ============================================================
// ListReviewsOptions ExcludeUserID Tests
// ============================================================

func TestListReviewsOptions_ExcludeUserID(t *testing.T) {
	// Test that ExcludeUserID field exists and works correctly
	opts := domain.ListReviewsOptions{
		EntityType:    domain.EntityTypeHotel,
		EntityID:      "hotel-123",
		PageSize:      10,
		ExcludeUserID: "user-to-exclude",
	}

	assert.Equal(t, "user-to-exclude", opts.ExcludeUserID)
	assert.Equal(t, domain.EntityTypeHotel, opts.EntityType)
	assert.Equal(t, "hotel-123", opts.EntityID)
	assert.Equal(t, int32(10), opts.PageSize)
}
