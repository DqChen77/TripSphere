package repository

import (
	"context"
	"database/sql"
	"regexp"
	"testing"
	"time"

	"github.com/DATA-DOG/go-sqlmock"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	"gorm.io/driver/mysql"
	"gorm.io/gorm"
	"gorm.io/gorm/logger"

	"trip-review-service/internal/domain"
)

// ============================================================
// Test Helpers
// ============================================================

func setupMockDB(t *testing.T) (*gorm.DB, sqlmock.Sqlmock, func()) {
	db, mock, err := sqlmock.New()
	require.NoError(t, err)

	dialector := mysql.New(mysql.Config{
		Conn:                      db,
		SkipInitializeWithVersion: true,
	})

	gormDB, err := gorm.Open(dialector, &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	require.NoError(t, err)

	cleanup := func() {
		db.Close()
	}

	return gormDB, mock, cleanup
}

func newTestReview() *domain.Review {
	now := time.Now()
	return &domain.Review{
		ID:         "review-123",
		UserID:     "user-456",
		TargetType: domain.ReviewTargetHotel,
		TargetID:   "hotel-789",
		Rating:     5,
		Text:       "Great hotel!",
		Images:     []string{"img1.jpg", "img2.jpg"},
		CreatedAt:  now,
		UpdatedAt:  now,
	}
}

// ============================================================
// Create Tests
// ============================================================

func TestReviewRepo_Create(t *testing.T) {
	tests := []struct {
		name      string
		review    *domain.Review
		setupMock func(sqlmock.Sqlmock, *domain.Review)
		wantErr   bool
	}{
		{
			name:   "successfully create review",
			review: newTestReview(),
			setupMock: func(mock sqlmock.Sqlmock, review *domain.Review) {
				mock.ExpectBegin()
				mock.ExpectExec(regexp.QuoteMeta("INSERT INTO `reviews`")).
					WithArgs(
						review.ID,
						review.UserID,
						string(review.TargetType),
						review.TargetID,
						review.Rating,
						review.Text,
						sqlmock.AnyArg(), // images JSON
						sqlmock.AnyArg(), // created_at
						sqlmock.AnyArg(), // updated_at
					).
					WillReturnResult(sqlmock.NewResult(1, 1))
				mock.ExpectCommit()
			},
			wantErr: false,
		},
		{
			name:   "database insert error",
			review: newTestReview(),
			setupMock: func(mock sqlmock.Sqlmock, review *domain.Review) {
				mock.ExpectBegin()
				mock.ExpectExec(regexp.QuoteMeta("INSERT INTO `reviews`")).
					WithArgs(
						review.ID,
						review.UserID,
						string(review.TargetType),
						review.TargetID,
						review.Rating,
						review.Text,
						sqlmock.AnyArg(),
						sqlmock.AnyArg(),
						sqlmock.AnyArg(),
					).
					WillReturnError(sql.ErrConnDone)
				mock.ExpectRollback()
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			db, mock, cleanup := setupMockDB(t)
			defer cleanup()

			repo := NewReviewRepo(db)
			tt.setupMock(mock, tt.review)

			err := repo.Create(context.Background(), tt.review)

			if tt.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
			}

			assert.NoError(t, mock.ExpectationsWereMet())
		})
	}
}

// ============================================================
// GetByID Tests
// ============================================================

func TestReviewRepo_GetByID(t *testing.T) {
	now := time.Now()

	tests := []struct {
		name       string
		id         string
		setupMock  func(sqlmock.Sqlmock)
		wantReview *domain.Review
		wantErr    bool
	}{
		{
			name: "successfully get review",
			id:   "review-123",
			setupMock: func(mock sqlmock.Sqlmock) {
				rows := sqlmock.NewRows([]string{
					"id", "user_id", "target_type", "target_id", "rating", "text", "images", "created_at", "updated_at",
				}).AddRow(
					"review-123", "user-456", "hotel", "hotel-789", 5, "Great!", `["img1.jpg"]`, now, now,
				)
				// GORM First() generates ORDER BY primary_key LIMIT 1
				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews` WHERE id = ? ORDER BY `reviews`.`id` LIMIT ?")).
					WithArgs("review-123", 1).
					WillReturnRows(rows)
			},
			wantReview: &domain.Review{
				ID:         "review-123",
				UserID:     "user-456",
				TargetType: domain.ReviewTargetHotel,
				TargetID:   "hotel-789",
				Rating:     5,
				Text:       "Great!",
				Images:     []string{"img1.jpg"},
			},
			wantErr: false,
		},
		{
			name: "review not found",
			id:   "non-existent",
			setupMock: func(mock sqlmock.Sqlmock) {
				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews` WHERE id = ? ORDER BY `reviews`.`id` LIMIT ?")).
					WithArgs("non-existent", 1).
					WillReturnError(gorm.ErrRecordNotFound)
			},
			wantReview: nil,
			wantErr:    false, // returns nil, nil which is not an error
		},
		{
			name: "database error",
			id:   "review-123",
			setupMock: func(mock sqlmock.Sqlmock) {
				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews` WHERE id = ? ORDER BY `reviews`.`id` LIMIT ?")).
					WithArgs("review-123", 1).
					WillReturnError(sql.ErrConnDone)
			},
			wantReview: nil,
			wantErr:    true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			db, mock, cleanup := setupMockDB(t)
			defer cleanup()

			repo := NewReviewRepo(db)
			tt.setupMock(mock)

			review, err := repo.GetByID(context.Background(), tt.id)

			if tt.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
				if tt.wantReview == nil {
					assert.Nil(t, review)
				} else {
					assert.NotNil(t, review)
					assert.Equal(t, tt.wantReview.ID, review.ID)
					assert.Equal(t, tt.wantReview.UserID, review.UserID)
					assert.Equal(t, tt.wantReview.Rating, review.Rating)
				}
			}

			assert.NoError(t, mock.ExpectationsWereMet())
		})
	}
}

// ============================================================
// FindByTarget Tests
// ============================================================

func TestReviewRepo_FindByTarget(t *testing.T) {
	now := time.Now()

	tests := []struct {
		name       string
		targetType domain.ReviewTargetType
		targetID   string
		offset     int64
		limit      int64
		setupMock  func(sqlmock.Sqlmock)
		wantCount  int
		wantErr    bool
	}{
		{
			name:       "successfully get review list",
			targetType: domain.ReviewTargetHotel,
			targetID:   "hotel-123",
			offset:     0,
			limit:      10,
			setupMock: func(mock sqlmock.Sqlmock) {
				rows := sqlmock.NewRows([]string{
					"id", "user_id", "target_type", "target_id", "rating", "text", "images", "created_at", "updated_at",
				}).
					AddRow("review-1", "user-1", "hotel", "hotel-123", 5, "Great!", `[]`, now, now).
					AddRow("review-2", "user-2", "hotel", "hotel-123", 4, "Good", `[]`, now, now)

				// GORM generates LIMIT ? OFFSET ? format (even when OFFSET is 0)
				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews` WHERE target_type = ? AND target_id = ? ORDER BY created_at DESC LIMIT ?")).
					WithArgs("hotel", "hotel-123", 10).
					WillReturnRows(rows)
			},
			wantCount: 2,
			wantErr:   false,
		},
		{
			name:       "with pagination offset",
			targetType: domain.ReviewTargetHotel,
			targetID:   "hotel-123",
			offset:     10,
			limit:      10,
			setupMock: func(mock sqlmock.Sqlmock) {
				rows := sqlmock.NewRows([]string{
					"id", "user_id", "target_type", "target_id", "rating", "text", "images", "created_at", "updated_at",
				})
				// Query with OFFSET
				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews` WHERE target_type = ? AND target_id = ? ORDER BY created_at DESC LIMIT ? OFFSET ?")).
					WithArgs("hotel", "hotel-123", 10, 10).
					WillReturnRows(rows)
			},
			wantCount: 0,
			wantErr:   false,
		},
		{
			name:       "database query error",
			targetType: domain.ReviewTargetHotel,
			targetID:   "hotel-123",
			offset:     0,
			limit:      10,
			setupMock: func(mock sqlmock.Sqlmock) {
				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews`")).
					WillReturnError(sql.ErrConnDone)
			},
			wantCount: 0,
			wantErr:   true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			db, mock, cleanup := setupMockDB(t)
			defer cleanup()

			repo := NewReviewRepo(db)
			tt.setupMock(mock)

			reviews, err := repo.FindByTarget(context.Background(), tt.targetType, tt.targetID, tt.offset, tt.limit)

			if tt.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
				assert.Len(t, reviews, tt.wantCount)
			}

			assert.NoError(t, mock.ExpectationsWereMet())
		})
	}
}

// ============================================================
// FindByTargetWithCursor Tests
// ============================================================

func TestReviewRepo_FindByTargetWithCursor(t *testing.T) {
	now := time.Now()

	tests := []struct {
		name       string
		targetType domain.ReviewTargetType
		targetID   string
		cursor     string
		limit      int64
		setupMock  func(sqlmock.Sqlmock)
		wantCount  int
		wantCursor bool // whether to expect a cursor in the response
		wantErr    bool
	}{
		{
			name:       "first query without cursor",
			targetType: domain.ReviewTargetHotel,
			targetID:   "hotel-123",
			cursor:     "",
			limit:      10,
			setupMock: func(mock sqlmock.Sqlmock) {
				rows := sqlmock.NewRows([]string{
					"id", "user_id", "target_type", "target_id", "rating", "text", "images", "created_at", "updated_at",
				}).AddRow("review-1", "user-1", "hotel", "hotel-123", 5, "Great!", `[]`, now, now)

				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews` WHERE target_type = ? AND target_id = ? ORDER BY created_at DESC LIMIT ?")).
					WithArgs("hotel", "hotel-123", 10).
					WillReturnRows(rows)
			},
			wantCount:  1,
			wantCursor: true,
			wantErr:    false,
		},
		{
			name:       "paginated query with cursor",
			targetType: domain.ReviewTargetHotel,
			targetID:   "hotel-123",
			cursor:     "1704067200", // Unix timestamp
			limit:      10,
			setupMock: func(mock sqlmock.Sqlmock) {
				rows := sqlmock.NewRows([]string{
					"id", "user_id", "target_type", "target_id", "rating", "text", "images", "created_at", "updated_at",
				}).AddRow("review-2", "user-2", "hotel", "hotel-123", 4, "Good", `[]`, now.Add(-time.Hour), now)

				// GORM wraps the first Where condition in parentheses
				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews` WHERE (target_type = ? AND target_id = ?) AND created_at < ? ORDER BY created_at DESC LIMIT ?")).
					WithArgs("hotel", "hotel-123", sqlmock.AnyArg(), 10).
					WillReturnRows(rows)
			},
			wantCount:  1,
			wantCursor: true,
			wantErr:    false,
		},
		{
			name:       "invalid cursor format",
			targetType: domain.ReviewTargetHotel,
			targetID:   "hotel-123",
			cursor:     "invalid-cursor",
			limit:      10,
			setupMock:  func(mock sqlmock.Sqlmock) {},
			wantCount:  0,
			wantCursor: false,
			wantErr:    true,
		},
		{
			name:       "empty result returns empty cursor",
			targetType: domain.ReviewTargetHotel,
			targetID:   "hotel-123",
			cursor:     "",
			limit:      10,
			setupMock: func(mock sqlmock.Sqlmock) {
				rows := sqlmock.NewRows([]string{
					"id", "user_id", "target_type", "target_id", "rating", "text", "images", "created_at", "updated_at",
				})
				mock.ExpectQuery(regexp.QuoteMeta("SELECT * FROM `reviews`")).
					WillReturnRows(rows)
			},
			wantCount:  0,
			wantCursor: false,
			wantErr:    false,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			db, mock, cleanup := setupMockDB(t)
			defer cleanup()

			repo := NewReviewRepo(db)
			tt.setupMock(mock)

			reviews, nextCursor, err := repo.FindByTargetWithCursor(
				context.Background(), tt.targetType, tt.targetID, tt.cursor, tt.limit,
			)

			if tt.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
				assert.Len(t, reviews, tt.wantCount)
				if tt.wantCursor {
					assert.NotEmpty(t, nextCursor)
				} else {
					assert.Empty(t, nextCursor)
				}
			}

			assert.NoError(t, mock.ExpectationsWereMet())
		})
	}
}

// ============================================================
// Update Tests
// ============================================================

func TestReviewRepo_Update(t *testing.T) {
	tests := []struct {
		name      string
		review    *domain.Review
		setupMock func(sqlmock.Sqlmock, *domain.Review)
		wantErr   bool
	}{
		{
			name:   "successfully update review",
			review: newTestReview(),
			setupMock: func(mock sqlmock.Sqlmock, review *domain.Review) {
				mock.ExpectBegin()
				mock.ExpectExec(regexp.QuoteMeta("UPDATE `reviews` SET")).
					WithArgs(
						sqlmock.AnyArg(), // images
						review.Rating,
						review.Text,
						sqlmock.AnyArg(), // updated_at
						review.ID,
					).
					WillReturnResult(sqlmock.NewResult(0, 1))
				mock.ExpectCommit()
			},
			wantErr: false,
		},
		{
			name:   "review not found",
			review: newTestReview(),
			setupMock: func(mock sqlmock.Sqlmock, review *domain.Review) {
				mock.ExpectBegin()
				mock.ExpectExec(regexp.QuoteMeta("UPDATE `reviews` SET")).
					WithArgs(
						sqlmock.AnyArg(),
						review.Rating,
						review.Text,
						sqlmock.AnyArg(),
						review.ID,
					).
					WillReturnResult(sqlmock.NewResult(0, 0)) // 0 rows affected
				mock.ExpectCommit()
			},
			wantErr: true,
		},
		{
			name:   "database update error",
			review: newTestReview(),
			setupMock: func(mock sqlmock.Sqlmock, review *domain.Review) {
				mock.ExpectBegin()
				mock.ExpectExec(regexp.QuoteMeta("UPDATE `reviews` SET")).
					WillReturnError(sql.ErrConnDone)
				mock.ExpectRollback()
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			db, mock, cleanup := setupMockDB(t)
			defer cleanup()

			repo := NewReviewRepo(db)
			tt.setupMock(mock, tt.review)

			err := repo.Update(context.Background(), tt.review)

			if tt.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
			}

			assert.NoError(t, mock.ExpectationsWereMet())
		})
	}
}

// ============================================================
// Delete Tests
// ============================================================

func TestReviewRepo_Delete(t *testing.T) {
	tests := []struct {
		name      string
		id        string
		setupMock func(sqlmock.Sqlmock)
		wantErr   bool
	}{
		{
			name: "successfully delete review",
			id:   "review-123",
			setupMock: func(mock sqlmock.Sqlmock) {
				mock.ExpectBegin()
				mock.ExpectExec(regexp.QuoteMeta("DELETE FROM `reviews` WHERE id = ?")).
					WithArgs("review-123").
					WillReturnResult(sqlmock.NewResult(0, 1))
				mock.ExpectCommit()
			},
			wantErr: false,
		},
		{
			name: "review not found",
			id:   "non-existent",
			setupMock: func(mock sqlmock.Sqlmock) {
				mock.ExpectBegin()
				mock.ExpectExec(regexp.QuoteMeta("DELETE FROM `reviews` WHERE id = ?")).
					WithArgs("non-existent").
					WillReturnResult(sqlmock.NewResult(0, 0)) // 0 rows affected
				mock.ExpectCommit()
			},
			wantErr: true,
		},
		{
			name: "database delete error",
			id:   "review-123",
			setupMock: func(mock sqlmock.Sqlmock) {
				mock.ExpectBegin()
				mock.ExpectExec(regexp.QuoteMeta("DELETE FROM `reviews`")).
					WillReturnError(sql.ErrConnDone)
				mock.ExpectRollback()
			},
			wantErr: true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			db, mock, cleanup := setupMockDB(t)
			defer cleanup()

			repo := NewReviewRepo(db)
			tt.setupMock(mock)

			err := repo.Delete(context.Background(), tt.id)

			if tt.wantErr {
				assert.Error(t, err)
			} else {
				assert.NoError(t, err)
			}

			assert.NoError(t, mock.ExpectationsWereMet())
		})
	}
}

// ============================================================
// Interface Compliance Test
// ============================================================

func TestReviewRepo_ImplementsInterface(t *testing.T) {
	db, _, cleanup := setupMockDB(t)
	defer cleanup()

	repo := NewReviewRepo(db)

	// Compile-time check for interface implementation
	var _ domain.ReviewRepository = repo
}

// ============================================================
// Benchmark Tests
// ============================================================

func BenchmarkReviewRepo_Create(b *testing.B) {
	db, mock, err := sqlmock.New()
	if err != nil {
		b.Fatal(err)
	}
	defer db.Close()

	dialector := mysql.New(mysql.Config{
		Conn:                      db,
		SkipInitializeWithVersion: true,
	})
	gormDB, err := gorm.Open(dialector, &gorm.Config{
		Logger: logger.Default.LogMode(logger.Silent),
	})
	if err != nil {
		b.Fatal(err)
	}

	repo := NewReviewRepo(gormDB)
	review := newTestReview()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		mock.ExpectBegin()
		mock.ExpectExec("INSERT").WillReturnResult(sqlmock.NewResult(1, 1))
		mock.ExpectCommit()

		_ = repo.Create(context.Background(), review)
	}
}
