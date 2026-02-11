package repository

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"time"

	"gorm.io/gorm"

	"trip-review-service/internal/domain"
	"trip-review-service/internal/repository/model"
)

// ReviewRepo implements domain.ReviewRepository
type ReviewRepo struct {
	db *gorm.DB
}

// NewReviewRepo creates a new ReviewRepo
func NewReviewRepo(db *gorm.DB) *ReviewRepo {
	return &ReviewRepo{db: db}
}

// Ensure ReviewRepo implements domain.ReviewRepository
var _ domain.ReviewRepository = (*ReviewRepo)(nil)

// Create - creates a review
func (r *ReviewRepo) Create(ctx context.Context, review *domain.Review) error {
	// Convert domain entity to DB model
	reviewModel := model.ToModel(review)

	// Write to database
	if err := r.db.WithContext(ctx).Create(reviewModel).Error; err != nil {
		return err
	}

	return nil
}

// GetByID - retrieves a single review by ID
func (r *ReviewRepo) GetByID(ctx context.Context, id string) (*domain.Review, error) {
	var m model.ReviewModel

	// Query
	if err := r.db.WithContext(ctx).Where("id = ?", id).First(&m).Error; err != nil {
		if errors.Is(err, gorm.ErrRecordNotFound) {
			// Can return a specific domain error, or simply return nil, nil
			return nil, nil
		}
		return nil, err
	}

	// Convert back to domain object
	return model.ToDomain(&m), nil
}

// FindByTarget queries a list of reviews (e.g., all reviews for a hotel)
// with pagination support
func (r *ReviewRepo) FindByTarget(ctx context.Context, targetType domain.ReviewTargetType, targetID string, offset, limit int64) ([]domain.Review, error) {
	var models []model.ReviewModel

	// Build query
	// Uses index: idx_target (target_type, target_id)
	query := r.db.WithContext(ctx).
		Where("target_type = ? AND target_id = ?", targetType, targetID).
		Order("created_at DESC"). // Usually ordered by time descending
		Offset(int(offset)).
		Limit(int(limit))

	if err := query.Find(&models).Error; err != nil {
		return nil, err
	}

	// Convert list
	reviews := make([]domain.Review, 0, len(models))
	for _, m := range models {
		temp := m
		reviews = append(reviews, *model.ToDomain(&temp))
	}

	return reviews, nil
}

func (r *ReviewRepo) FindByTargetWithCursor(ctx context.Context, targetType domain.ReviewTargetType, targetID string, cursor string, limit int64) ([]domain.Review, string, error) {
	var models []model.ReviewModel

	query := r.db.WithContext(ctx).
		Where("target_type = ? AND target_id = ?", targetType, targetID)

	if cursor != "" {
		cursorInt, err := strconv.ParseInt(cursor, 10, 64)
		if err != nil {
			return nil, "", fmt.Errorf("invalid cursor format: %w", err)
		}
		cursorTime := time.Unix(cursorInt, 0)
		query = query.Where("created_at < ?", cursorTime)
	}
	query = query.Order("created_at DESC").Limit(int(limit))

	if err := query.Find(&models).Error; err != nil {
		return nil, "", err
	}

	reviews := make([]domain.Review, 0, len(models))
	var nextCursor string

	for _, m := range models {
		reviews = append(reviews, *model.ToDomain(&m))
	}

	// Use Unix timestamp as cursor to maintain a consistent format
	if len(models) > 0 {
		lastModel := models[len(models)-1]
		nextCursor = strconv.FormatInt(lastModel.CreatedAt.Unix(), 10)
	}

	return reviews, nextCursor, nil
}

// Update updates a review (e.g., user modifies rating or content)
func (r *ReviewRepo) Update(ctx context.Context, review *domain.Review) error {
	// This map specifies which fields to update
	// Using Updates avoids updating unset zero-value fields to the database
	updates := map[string]interface{}{
		"text":       review.Text,
		"rating":     review.Rating,
		"images":     model.StringArray(review.Images), // Force type conversion
		"updated_at": review.UpdatedAt,
	}

	result := r.db.WithContext(ctx).
		Model(&model.ReviewModel{}).
		Where("id = ? ", review.ID). // For safety, include user_id to verify ownership
		Updates(updates)

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("review not found or permission denied")
	}
	return nil
}

// Delete deletes a review
func (r *ReviewRepo) Delete(ctx context.Context, id string) error {
	result := r.db.WithContext(ctx).
		Where("id = ? ", id).
		Delete(&model.ReviewModel{})

	if result.Error != nil {
		return result.Error
	}
	if result.RowsAffected == 0 {
		return errors.New("review not found")
	}
	return nil
}
