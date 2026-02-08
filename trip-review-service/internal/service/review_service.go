package service

import (
	"context"
	"log/slog"
	"time"

	"github.com/google/uuid"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	pd "trip-review-service/api/grpc/gen/tripsphere/review/v1"
	"trip-review-service/internal/domain"
)

// ReviewService implements the gRPC ReviewServiceServer
type ReviewService struct {
	pd.UnimplementedReviewServiceServer
	repo domain.ReviewRepository
}

// NewReviewService creates a new ReviewService with the given repository
func NewReviewService(repo domain.ReviewRepository) *ReviewService {
	return &ReviewService{repo: repo}
}

// CreateReview creates a new review
func (s *ReviewService) CreateReview(ctx context.Context, req *pd.CreateReviewRequest) (*pd.CreateReviewResponse, error) {
	// Input validation
	if req.UserId == "" {
		return nil, status.Error(codes.InvalidArgument, "user_id is required")
	}
	if req.TargetId == "" {
		return nil, status.Error(codes.InvalidArgument, "target_id is required")
	}
	if req.TargetType == "" {
		return nil, status.Error(codes.InvalidArgument, "target_type is required")
	}
	if req.Rating < 1 || req.Rating > 5 {
		return nil, status.Error(codes.InvalidArgument, "rating must be between 1 and 5")
	}

	id := uuid.New().String()
	now := time.Now()

	review := &domain.Review{
		ID:         id,
		UserID:     req.UserId,
		TargetType: domain.ReviewTargetType(req.TargetType),
		TargetID:   req.TargetId,
		Rating:     req.Rating,
		Text:       req.Text,
		Images:     req.Images,
		CreatedAt:  now,
		UpdatedAt:  now,
	}

	if err := s.repo.Create(ctx, review); err != nil {
		slog.ErrorContext(ctx, "failed to create review",
			"error", err,
			"user_id", req.UserId,
			"target_id", req.TargetId,
		)
		return nil, status.Errorf(codes.Internal, "failed to create review: %v", err)
	}

	slog.InfoContext(ctx, "review created",
		"id", id,
		"user_id", req.UserId,
		"target_id", req.TargetId,
	)

	return &pd.CreateReviewResponse{
		Id:     id,
		Status: true,
	}, nil
}

// UpdateReview updates an existing review
func (s *ReviewService) UpdateReview(ctx context.Context, req *pd.UpdateReviewRequest) (*pd.UpdateReviewResponse, error) {
	// Input validation
	if req.Id == "" {
		return nil, status.Error(codes.InvalidArgument, "id is required")
	}
	if req.Rating < 1 || req.Rating > 5 {
		return nil, status.Error(codes.InvalidArgument, "rating must be between 1 and 5")
	}

	review := &domain.Review{
		ID:        req.Id,
		Rating:    req.Rating,
		Text:      req.Text,
		Images:    req.Images,
		UpdatedAt: time.Now(),
	}

	if err := s.repo.Update(ctx, review); err != nil {
		slog.ErrorContext(ctx, "failed to update review",
			"error", err,
			"id", req.Id,
		)
		return nil, status.Errorf(codes.Internal, "failed to update review: %v", err)
	}

	slog.InfoContext(ctx, "review updated", "id", req.Id)

	return &pd.UpdateReviewResponse{Status: true}, nil
}

// DeleteReview deletes a review by ID
func (s *ReviewService) DeleteReview(ctx context.Context, req *pd.DeleteReviewRequest) (*pd.DeleteReviewResponse, error) {
	if req.Id == "" {
		return nil, status.Error(codes.InvalidArgument, "id is required")
	}

	if err := s.repo.Delete(ctx, req.Id); err != nil {
		slog.ErrorContext(ctx, "failed to delete review",
			"error", err,
			"id", req.Id,
		)
		return nil, status.Errorf(codes.Internal, "failed to delete review: %v", err)
	}

	slog.InfoContext(ctx, "review deleted", "id", req.Id)

	return &pd.DeleteReviewResponse{}, nil
}

// GetReviewByTargetID gets reviews by target ID with pagination
func (s *ReviewService) GetReviewByTargetID(ctx context.Context, req *pd.GetReviewByTargetIDRequest) (*pd.GetReviewByTargetIDResponse, error) {
	// Input validation
	if req.TargetId == "" {
		return nil, status.Error(codes.InvalidArgument, "target_id is required")
	}
	if req.PageSize <= 0 {
		req.PageSize = 10 // default page size
	}
	if req.PageNumber <= 0 {
		req.PageNumber = 1 // default page number
	}

	offset := req.PageSize * (req.PageNumber - 1)
	reviews, err := s.repo.FindByTarget(ctx, domain.ReviewTargetType(req.TargetType), req.TargetId, offset, req.PageSize)
	if err != nil {
		slog.ErrorContext(ctx, "failed to get reviews by target",
			"error", err,
			"target_type", req.TargetType,
			"target_id", req.TargetId,
		)
		return nil, status.Errorf(codes.Internal, "failed to get reviews: %v", err)
	}

	pbReviews := make([]*pd.Review, 0, len(reviews))
	for _, review := range reviews {
		pbReviews = append(pbReviews, domainToProto(&review))
	}

	return &pd.GetReviewByTargetIDResponse{
		Reviews:      pbReviews,
		TotalReviews: int64(len(pbReviews)),
		Status:       true,
	}, nil
}

// GetReviewByTargetIDWithCursor gets reviews by target ID with cursor-based pagination
func (s *ReviewService) GetReviewByTargetIDWithCursor(ctx context.Context, req *pd.GetReviewByTargetIDWithCursorRequest) (*pd.GetReviewByTargetIDWithCursorResponse, error) {
	// Input validation
	if req.TargetId == "" {
		return nil, status.Error(codes.InvalidArgument, "target_id is required")
	}
	if req.Limit <= 0 {
		req.Limit = 10 // default limit
	}

	reviews, nextCursor, err := s.repo.FindByTargetWithCursor(ctx, domain.ReviewTargetType(req.TargetType), req.TargetId, req.Cursor, req.Limit)
	if err != nil {
		slog.ErrorContext(ctx, "failed to get reviews by target with cursor",
			"error", err,
			"target_type", req.TargetType,
			"target_id", req.TargetId,
			"cursor", req.Cursor,
		)
		return nil, status.Errorf(codes.Internal, "failed to get reviews: %v", err)
	}

	pbReviews := make([]*pd.Review, 0, len(reviews))
	for _, review := range reviews {
		pbReviews = append(pbReviews, domainToProto(&review))
	}

	return &pd.GetReviewByTargetIDWithCursorResponse{
		Reviews:      pbReviews,
		TotalReviews: int64(len(pbReviews)),
		NextCursor:   nextCursor,
		Status:       true,
	}, nil
}

// domainToProto converts a domain.Review to a protobuf Review
func domainToProto(review *domain.Review) *pd.Review {
	return &pd.Review{
		Id:         review.ID,
		UserId:     review.UserID,
		TargetType: string(review.TargetType),
		TargetId:   review.TargetID,
		Rating:     review.Rating,
		Text:       review.Text,
		Images:     review.Images,
		CreatedAt:  review.CreatedAt.Unix(),
		UpdatedAt:  review.UpdatedAt.Unix(),
	}
}
