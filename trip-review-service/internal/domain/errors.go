package domain

import "errors"

// 领域层错误定义
// 这些错误用于在领域层和服务层之间传递业务逻辑错误
var (
	// ErrReviewNotFound 表示请求的评论不存在
	ErrReviewNotFound = errors.New("review not found")

	// ErrPermissionDenied 表示用户没有权限执行该操作
	ErrPermissionDenied = errors.New("permission denied")

	// ErrInvalidRating 表示评分值无效
	ErrInvalidRating = errors.New("rating must be between 1 and 5")

	// ErrInvalidCursor 表示分页游标格式无效
	ErrInvalidCursor = errors.New("invalid cursor format")

	// ErrEmptyUserID 表示用户ID为空
	ErrEmptyUserID = errors.New("user_id is required")

	// ErrEmptyTargetID 表示目标ID为空
	ErrEmptyTargetID = errors.New("target_id is required")

	// ErrEmptyTargetType 表示目标类型为空
	ErrEmptyTargetType = errors.New("target_type is required")

	// ErrEmptyReviewID 表示评论ID为空
	ErrEmptyReviewID = errors.New("review id is required")
)

// IsNotFoundError 检查错误是否为"未找到"类型
func IsNotFoundError(err error) bool {
	return errors.Is(err, ErrReviewNotFound)
}
