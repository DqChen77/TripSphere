package service

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/mock"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	pd "trip-review-service/api/grpc/gen/tripsphere/review/v1"
	"trip-review-service/internal/domain"
	domainmock "trip-review-service/internal/domain/mock"
)

// ============================================================
// Test Helpers
// ============================================================

func setupTest(t *testing.T) (*ReviewService, *domainmock.MockReviewRepository) {
	mockRepo := domainmock.NewMockReviewRepository()
	service := NewReviewService(mockRepo)
	return service, mockRepo
}

func assertGRPCErrorCode(t *testing.T, err error, expectedCode codes.Code) {
	t.Helper()
	assert.Error(t, err)
	st, ok := status.FromError(err)
	assert.True(t, ok, "error should be a gRPC status error")
	assert.Equal(t, expectedCode, st.Code())
}

// ============================================================
// CreateReview Tests
// ============================================================

func TestCreateReview(t *testing.T) {
	tests := []struct {
		name          string
		request       *pd.CreateReviewRequest
		setupMock     func(*domainmock.MockReviewRepository)
		expectedCode  codes.Code
		expectedError bool
		checkResponse func(*testing.T, *pd.CreateReviewResponse)
	}{
		{
			name: "successfully create review",
			request: &pd.CreateReviewRequest{
				UserId:     "user-123",
				TargetId:   "hotel-456",
				TargetType: "hotel",
				Rating:     5,
				Text:       "Great hotel!",
				Images:     []string{"img1.jpg", "img2.jpg"},
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("Create", mock.Anything, mock.MatchedBy(func(r *domain.Review) bool {
					return r.UserID == "user-123" &&
						r.TargetID == "hotel-456" &&
						r.TargetType == domain.ReviewTargetHotel &&
						r.Rating == 5
				})).Return(nil)
			},
			expectedError: false,
			checkResponse: func(t *testing.T, resp *pd.CreateReviewResponse) {
				assert.True(t, resp.Status)
				assert.NotEmpty(t, resp.Id)
			},
		},
		{
			name: "empty user ID",
			request: &pd.CreateReviewRequest{
				UserId:     "",
				TargetId:   "hotel-456",
				TargetType: "hotel",
				Rating:     5,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "empty target ID",
			request: &pd.CreateReviewRequest{
				UserId:     "user-123",
				TargetId:   "",
				TargetType: "hotel",
				Rating:     5,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "empty target type",
			request: &pd.CreateReviewRequest{
				UserId:     "user-123",
				TargetId:   "hotel-456",
				TargetType: "",
				Rating:     5,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "rating less than 1",
			request: &pd.CreateReviewRequest{
				UserId:     "user-123",
				TargetId:   "hotel-456",
				TargetType: "hotel",
				Rating:     0,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "rating greater than 5",
			request: &pd.CreateReviewRequest{
				UserId:     "user-123",
				TargetId:   "hotel-456",
				TargetType: "hotel",
				Rating:     6,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "database error",
			request: &pd.CreateReviewRequest{
				UserId:     "user-123",
				TargetId:   "hotel-456",
				TargetType: "hotel",
				Rating:     5,
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("Create", mock.Anything, mock.Anything).Return(errors.New("database error"))
			},
			expectedCode:  codes.Internal,
			expectedError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			service, mockRepo := setupTest(t)
			tt.setupMock(mockRepo)

			resp, err := service.CreateReview(context.Background(), tt.request)

			if tt.expectedError {
				assertGRPCErrorCode(t, err, tt.expectedCode)
				assert.Nil(t, resp)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
				if tt.checkResponse != nil {
					tt.checkResponse(t, resp)
				}
			}

			mockRepo.AssertExpectations(t)
		})
	}
}

// ============================================================
// UpdateReview Tests
// ============================================================

func TestUpdateReview(t *testing.T) {
	tests := []struct {
		name          string
		request       *pd.UpdateReviewRequest
		setupMock     func(*domainmock.MockReviewRepository)
		expectedCode  codes.Code
		expectedError bool
	}{
		{
			name: "successfully update review",
			request: &pd.UpdateReviewRequest{
				Id:     "review-123",
				Rating: 4,
				Text:   "Updated review",
				Images: []string{"new-img.jpg"},
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("Update", mock.Anything, mock.MatchedBy(func(r *domain.Review) bool {
					return r.ID == "review-123" && r.Rating == 4
				})).Return(nil)
			},
			expectedError: false,
		},
		{
			name: "empty review ID",
			request: &pd.UpdateReviewRequest{
				Id:     "",
				Rating: 4,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "invalid rating - less than 1",
			request: &pd.UpdateReviewRequest{
				Id:     "review-123",
				Rating: 0,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "invalid rating - greater than 5",
			request: &pd.UpdateReviewRequest{
				Id:     "review-123",
				Rating: 6,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "review not found",
			request: &pd.UpdateReviewRequest{
				Id:     "non-existent",
				Rating: 4,
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("Update", mock.Anything, mock.Anything).Return(errors.New("review not found or permission denied"))
			},
			expectedCode:  codes.Internal,
			expectedError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			service, mockRepo := setupTest(t)
			tt.setupMock(mockRepo)

			resp, err := service.UpdateReview(context.Background(), tt.request)

			if tt.expectedError {
				assertGRPCErrorCode(t, err, tt.expectedCode)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
				assert.True(t, resp.Status)
			}

			mockRepo.AssertExpectations(t)
		})
	}
}

// ============================================================
// DeleteReview Tests
// ============================================================

func TestDeleteReview(t *testing.T) {
	tests := []struct {
		name          string
		request       *pd.DeleteReviewRequest
		setupMock     func(*domainmock.MockReviewRepository)
		expectedCode  codes.Code
		expectedError bool
	}{
		{
			name: "successfully delete review",
			request: &pd.DeleteReviewRequest{
				Id: "review-123",
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("Delete", mock.Anything, "review-123").Return(nil)
			},
			expectedError: false,
		},
		{
			name: "empty review ID",
			request: &pd.DeleteReviewRequest{
				Id: "",
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "review not found",
			request: &pd.DeleteReviewRequest{
				Id: "non-existent",
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("Delete", mock.Anything, "non-existent").Return(errors.New("review not found"))
			},
			expectedCode:  codes.Internal,
			expectedError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			service, mockRepo := setupTest(t)
			tt.setupMock(mockRepo)

			resp, err := service.DeleteReview(context.Background(), tt.request)

			if tt.expectedError {
				assertGRPCErrorCode(t, err, tt.expectedCode)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
			}

			mockRepo.AssertExpectations(t)
		})
	}
}

// ============================================================
// GetReviewByTargetID Tests
// ============================================================

func TestGetReviewByTargetID(t *testing.T) {
	now := time.Now()
	sampleReviews := []domain.Review{
		{
			ID:         "review-1",
			UserID:     "user-1",
			TargetType: domain.ReviewTargetHotel,
			TargetID:   "hotel-123",
			Rating:     5,
			Text:       "Excellent!",
			Images:     []string{"img1.jpg"},
			CreatedAt:  now,
			UpdatedAt:  now,
		},
		{
			ID:         "review-2",
			UserID:     "user-2",
			TargetType: domain.ReviewTargetHotel,
			TargetID:   "hotel-123",
			Rating:     4,
			Text:       "Good",
			CreatedAt:  now.Add(-time.Hour),
			UpdatedAt:  now.Add(-time.Hour),
		},
	}

	tests := []struct {
		name           string
		request        *pd.GetReviewByTargetIDRequest
		setupMock      func(*domainmock.MockReviewRepository)
		expectedCode   codes.Code
		expectedError  bool
		expectedLength int
	}{
		{
			name: "successfully get review list",
			request: &pd.GetReviewByTargetIDRequest{
				TargetId:   "hotel-123",
				TargetType: "hotel",
				PageSize:   10,
				PageNumber: 1,
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("FindByTarget", mock.Anything, domain.ReviewTargetType("hotel"), "hotel-123", int64(0), int64(10)).
					Return(sampleReviews, nil)
			},
			expectedError:  false,
			expectedLength: 2,
		},
		{
			name: "use default pagination params",
			request: &pd.GetReviewByTargetIDRequest{
				TargetId:   "hotel-123",
				TargetType: "hotel",
				PageSize:   0, // should default to 10
				PageNumber: 0, // should default to 1
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("FindByTarget", mock.Anything, domain.ReviewTargetType("hotel"), "hotel-123", int64(0), int64(10)).
					Return(sampleReviews, nil)
			},
			expectedError:  false,
			expectedLength: 2,
		},
		{
			name: "empty target ID",
			request: &pd.GetReviewByTargetIDRequest{
				TargetId:   "",
				TargetType: "hotel",
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "second page data",
			request: &pd.GetReviewByTargetIDRequest{
				TargetId:   "hotel-123",
				TargetType: "hotel",
				PageSize:   10,
				PageNumber: 2,
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("FindByTarget", mock.Anything, domain.ReviewTargetType("hotel"), "hotel-123", int64(10), int64(10)).
					Return([]domain.Review{}, nil)
			},
			expectedError:  false,
			expectedLength: 0,
		},
		{
			name: "database error",
			request: &pd.GetReviewByTargetIDRequest{
				TargetId:   "hotel-123",
				TargetType: "hotel",
				PageSize:   10,
				PageNumber: 1,
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("FindByTarget", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
					Return(nil, errors.New("database error"))
			},
			expectedCode:  codes.Internal,
			expectedError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			service, mockRepo := setupTest(t)
			tt.setupMock(mockRepo)

			resp, err := service.GetReviewByTargetID(context.Background(), tt.request)

			if tt.expectedError {
				assertGRPCErrorCode(t, err, tt.expectedCode)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
				assert.Len(t, resp.Reviews, tt.expectedLength)
				assert.True(t, resp.Status)
			}

			mockRepo.AssertExpectations(t)
		})
	}
}

// ============================================================
// GetReviewByTargetIDWithCursor Tests
// ============================================================

func TestGetReviewByTargetIDWithCursor(t *testing.T) {
	now := time.Now()
	sampleReviews := []domain.Review{
		{
			ID:         "review-1",
			UserID:     "user-1",
			TargetType: domain.ReviewTargetHotel,
			TargetID:   "hotel-123",
			Rating:     5,
			Text:       "Excellent!",
			CreatedAt:  now,
			UpdatedAt:  now,
		},
	}

	tests := []struct {
		name           string
		request        *pd.GetReviewByTargetIDWithCursorRequest
		setupMock      func(*domainmock.MockReviewRepository)
		expectedCode   codes.Code
		expectedError  bool
		expectedLength int
		expectedCursor string
	}{
		{
			name: "successfully get reviews - no cursor",
			request: &pd.GetReviewByTargetIDWithCursorRequest{
				TargetId:   "hotel-123",
				TargetType: "hotel",
				Limit:      10,
				Cursor:     "",
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("FindByTargetWithCursor", mock.Anything, domain.ReviewTargetType("hotel"), "hotel-123", "", int64(10)).
					Return(sampleReviews, "1234567890", nil)
			},
			expectedError:  false,
			expectedLength: 1,
			expectedCursor: "1234567890",
		},
		{
			name: "successfully get reviews - with cursor",
			request: &pd.GetReviewByTargetIDWithCursorRequest{
				TargetId:   "hotel-123",
				TargetType: "hotel",
				Limit:      10,
				Cursor:     "1234567890",
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("FindByTargetWithCursor", mock.Anything, domain.ReviewTargetType("hotel"), "hotel-123", "1234567890", int64(10)).
					Return(sampleReviews, "1234567800", nil)
			},
			expectedError:  false,
			expectedLength: 1,
			expectedCursor: "1234567800",
		},
		{
			name: "use default limit",
			request: &pd.GetReviewByTargetIDWithCursorRequest{
				TargetId:   "hotel-123",
				TargetType: "hotel",
				Limit:      0, // should default to 10
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("FindByTargetWithCursor", mock.Anything, domain.ReviewTargetType("hotel"), "hotel-123", "", int64(10)).
					Return(sampleReviews, "1234567890", nil)
			},
			expectedError:  false,
			expectedLength: 1,
		},
		{
			name: "empty target ID",
			request: &pd.GetReviewByTargetIDWithCursorRequest{
				TargetId:   "",
				TargetType: "hotel",
				Limit:      10,
			},
			setupMock:     func(m *domainmock.MockReviewRepository) {},
			expectedCode:  codes.InvalidArgument,
			expectedError: true,
		},
		{
			name: "database error",
			request: &pd.GetReviewByTargetIDWithCursorRequest{
				TargetId:   "hotel-123",
				TargetType: "hotel",
				Limit:      10,
			},
			setupMock: func(m *domainmock.MockReviewRepository) {
				m.On("FindByTargetWithCursor", mock.Anything, mock.Anything, mock.Anything, mock.Anything, mock.Anything).
					Return(nil, "", errors.New("database error"))
			},
			expectedCode:  codes.Internal,
			expectedError: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			service, mockRepo := setupTest(t)
			tt.setupMock(mockRepo)

			resp, err := service.GetReviewByTargetIDWithCursor(context.Background(), tt.request)

			if tt.expectedError {
				assertGRPCErrorCode(t, err, tt.expectedCode)
			} else {
				assert.NoError(t, err)
				assert.NotNil(t, resp)
				assert.Len(t, resp.Reviews, tt.expectedLength)
				assert.True(t, resp.Status)
				if tt.expectedCursor != "" {
					assert.Equal(t, tt.expectedCursor, resp.NextCursor)
				}
			}

			mockRepo.AssertExpectations(t)
		})
	}
}

// ============================================================
// domainToProto Tests
// ============================================================

func TestDomainToProto(t *testing.T) {
	now := time.Now()
	review := &domain.Review{
		ID:         "review-123",
		UserID:     "user-456",
		TargetType: domain.ReviewTargetHotel,
		TargetID:   "hotel-789",
		Rating:     5,
		Text:       "Great experience!",
		Images:     []string{"img1.jpg", "img2.jpg"},
		CreatedAt:  now,
		UpdatedAt:  now,
	}

	proto := domainToProto(review)

	assert.Equal(t, review.ID, proto.Id)
	assert.Equal(t, review.UserID, proto.UserId)
	assert.Equal(t, string(review.TargetType), proto.TargetType)
	assert.Equal(t, review.TargetID, proto.TargetId)
	assert.Equal(t, review.Rating, proto.Rating)
	assert.Equal(t, review.Text, proto.Text)
	assert.Equal(t, review.Images, proto.Images)
	assert.Equal(t, review.CreatedAt.Unix(), proto.CreatedAt)
	assert.Equal(t, review.UpdatedAt.Unix(), proto.UpdatedAt)
}

// ============================================================
// Benchmark Tests
// ============================================================

func BenchmarkCreateReview(b *testing.B) {
	mockRepo := domainmock.NewMockReviewRepository()
	mockRepo.On("Create", mock.Anything, mock.Anything).Return(nil)
	service := NewReviewService(mockRepo)

	req := &pd.CreateReviewRequest{
		UserId:     "user-123",
		TargetId:   "hotel-456",
		TargetType: "hotel",
		Rating:     5,
		Text:       "Great hotel!",
	}

	ctx := context.Background()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_, _ = service.CreateReview(ctx, req)
	}
}

func BenchmarkDomainToProto(b *testing.B) {
	now := time.Now()
	review := &domain.Review{
		ID:         "review-123",
		UserID:     "user-456",
		TargetType: domain.ReviewTargetHotel,
		TargetID:   "hotel-789",
		Rating:     5,
		Text:       "Great experience!",
		Images:     []string{"img1.jpg", "img2.jpg"},
		CreatedAt:  now,
		UpdatedAt:  now,
	}

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = domainToProto(review)
	}
}
