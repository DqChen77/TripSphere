package mock

import (
	"context"

	"github.com/stretchr/testify/mock"

	"trip-review-service/internal/domain"
)

// MockReviewRepository 是 ReviewRepository 接口的 mock 实现
type MockReviewRepository struct {
	mock.Mock
}

// NewMockReviewRepository 创建一个新的 MockReviewRepository
func NewMockReviewRepository() *MockReviewRepository {
	return &MockReviewRepository{}
}

// Create mock 实现
func (m *MockReviewRepository) Create(ctx context.Context, review *domain.Review) error {
	args := m.Called(ctx, review)
	return args.Error(0)
}

// GetByID mock 实现
func (m *MockReviewRepository) GetByID(ctx context.Context, id string) (*domain.Review, error) {
	args := m.Called(ctx, id)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).(*domain.Review), args.Error(1)
}

// FindByTarget mock 实现
func (m *MockReviewRepository) FindByTarget(ctx context.Context, targetType domain.ReviewTargetType, targetID string, offset, limit int64) ([]domain.Review, error) {
	args := m.Called(ctx, targetType, targetID, offset, limit)
	if args.Get(0) == nil {
		return nil, args.Error(1)
	}
	return args.Get(0).([]domain.Review), args.Error(1)
}

// FindByTargetWithCursor mock 实现
func (m *MockReviewRepository) FindByTargetWithCursor(ctx context.Context, targetType domain.ReviewTargetType, targetID string, cursor string, limit int64) ([]domain.Review, string, error) {
	args := m.Called(ctx, targetType, targetID, cursor, limit)
	if args.Get(0) == nil {
		return nil, args.String(1), args.Error(2)
	}
	return args.Get(0).([]domain.Review), args.String(1), args.Error(2)
}

// Update mock 实现
func (m *MockReviewRepository) Update(ctx context.Context, review *domain.Review) error {
	args := m.Called(ctx, review)
	return args.Error(0)
}

// Delete mock 实现
func (m *MockReviewRepository) Delete(ctx context.Context, id string) error {
	args := m.Called(ctx, id)
	return args.Error(0)
}

// 确保 MockReviewRepository 实现了 domain.ReviewRepository 接口
var _ domain.ReviewRepository = (*MockReviewRepository)(nil)
