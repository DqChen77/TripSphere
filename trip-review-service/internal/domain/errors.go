package domain

import "errors"

// Domain layer error definitions
// These errors are used to communicate business logic errors between the domain and service layers
var (
	// ErrReviewNotFound indicates the requested review does not exist
	ErrReviewNotFound = errors.New("review not found")

	// ErrPermissionDenied indicates the user does not have permission to perform this operation
	ErrPermissionDenied = errors.New("permission denied")

	// ErrInvalidRating indicates the rating value is invalid
	ErrInvalidRating = errors.New("rating must be between 1 and 5")

	// ErrInvalidCursor indicates the pagination cursor format is invalid
	ErrInvalidCursor = errors.New("invalid cursor format")

	// ErrEmptyUserID indicates the user ID is empty
	ErrEmptyUserID = errors.New("user_id is required")

	// ErrEmptyTargetID indicates the target ID is empty
	ErrEmptyTargetID = errors.New("target_id is required")

	// ErrEmptyTargetType indicates the target type is empty
	ErrEmptyTargetType = errors.New("target_type is required")

	// ErrEmptyReviewID indicates the review ID is empty
	ErrEmptyReviewID = errors.New("review id is required")
)

// IsNotFoundError checks if the error is a "not found" type
func IsNotFoundError(err error) bool {
	return errors.Is(err, ErrReviewNotFound)
}
