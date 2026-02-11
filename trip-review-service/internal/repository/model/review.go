package model

import (
	"database/sql/driver"
	"encoding/json"
	"errors"
	"time"
	"trip-review-service/internal/domain"
)

type ReviewModel struct {
	ID         string `gorm:"primaryKey;column:id;type:varchar(64)"`
	UserID     string `gorm:"column:user_id;type:varchar(64);not null"`
	TargetType string `gorm:"column:target_type;type:varchar(20);not null"`
	TargetID   string `gorm:"column:target_id;type:varchar(64);not null"`
	Rating     int64  `gorm:"column:rating;type:tinyint;not null"`

	// GORM handles string as utf8 by default, but ensure the DB uses utf8mb4 when creating tables
	Text string `gorm:"column:text;type:text"`

	// Custom type for handling []string <-> JSON
	Images StringArray `gorm:"column:images;type:json"`

	CreatedAt time.Time `gorm:"autoCreateTime"`
	UpdatedAt time.Time `gorm:"autoUpdateTime"`
}

func (reviewModel *ReviewModel) TableName() string {
	return "reviews"
}

func ToDomain(reviewModel *ReviewModel) *domain.Review {
	return &domain.Review{
		ID:         reviewModel.ID,
		UserID:     reviewModel.UserID,
		TargetID:   reviewModel.TargetID,
		TargetType: domain.ReviewTargetType(reviewModel.TargetType),
		Rating:     reviewModel.Rating,
		Text:       reviewModel.Text,
		// Cast back to []string
		Images:    reviewModel.Images,
		CreatedAt: reviewModel.CreatedAt,
		UpdatedAt: reviewModel.UpdatedAt,
	}
}
func ToModel(review *domain.Review) *ReviewModel {
	return &ReviewModel{
		ID:         review.ID,
		UserID:     review.UserID,
		TargetType: string(review.TargetType),
		TargetID:   review.TargetID,
		Rating:     review.Rating,
		Text:       review.Text,
		// Direct type conversion since the underlying type is []string
		Images:    review.Images,
		CreatedAt: review.CreatedAt,
		UpdatedAt: review.UpdatedAt,
	}
}

// ==========================================================
// Custom type: StringArray (for handling JSON string arrays)
// ==========================================================

type StringArray []string

// Value  Go Struct -> DB (serializes to JSON string)
// When stored in the database, it becomes ["url1", "url2"]
func (s StringArray) Value() (driver.Value, error) {
	if len(s) == 0 {
		// Can also store nil or "[]" depending on business requirements; here we store an empty array string
		return "[]", nil
	}
	return json.Marshal(s)
}

// Scan  DB -> Go Struct (deserialization)
func (s *StringArray) Scan(value interface{}) error {
	if value == nil {
		*s = []string{}
		return nil
	}

	bytes, ok := value.([]byte)
	if !ok {
		// Compatible with some drivers that may return string
		if str, ok := value.(string); ok {
			bytes = []byte(str)
		} else {
			return errors.New("failed to scan StringArray: value is not []byte or string")
		}
	}

	return json.Unmarshal(bytes, s)
}
