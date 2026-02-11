package config

import (
	"fmt"
	"os"
	"strconv"
	"time"
)

// Config holds all configuration for the application
type Config struct {
	App   AppConfig
	Nacos NacosConfig
	MySQL MySQLConfig
}

// AppConfig holds application configuration
type AppConfig struct {
	Env   string
	Name  string
	Port  int
	PodIP string
}

// NacosConfig holds Nacos configuration
type NacosConfig struct {
	Host      string
	Port      int
	Namespace string
	Group     string
	Username  string
	Password  string
}

// MySQLConfig holds MySQL configuration
type MySQLConfig struct {
	Host            string
	Port            int
	User            string
	Password        string
	Database        string
	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime time.Duration
}

// DSN returns the MySQL data source name
func (c *MySQLConfig) DSN() string {
	return fmt.Sprintf("%s:%s@tcp(%s:%d)/%s?parseTime=true&charset=utf8mb4&loc=Local",
		c.User, c.Password, c.Host, c.Port, c.Database)
}

// Load loads configuration from environment variables
func Load() (*Config, error) {
	port, err := strconv.Atoi(getEnv("PORT", "50057"))
	if err != nil {
		return nil, fmt.Errorf("invalid PORT: %w", err)
	}

	nacosPort, err := strconv.Atoi(getEnv("NACOS_PORT", "8848"))
	if err != nil {
		return nil, fmt.Errorf("invalid NACOS_PORT: %w", err)
	}

	mysqlPort, err := strconv.Atoi(getEnv("MYSQL_PORT", "3306"))
	if err != nil {
		return nil, fmt.Errorf("invalid MYSQL_PORT: %w", err)
	}

	return &Config{
		App: AppConfig{
			Env:   getEnv("APP_ENV", "dev"),
			Name:  getEnv("APP_NAME", "trip-review-service"),
			Port:  port,
			PodIP: getEnv("POD_IP", ""),
		},
		Nacos: NacosConfig{
			Host:      getEnv("NACOS_HOST", ""),
			Port:      nacosPort,
			Namespace: getEnv("NACOS_NAMESPACE", "public"),
			Group:     getEnv("NACOS_GROUP", "DEFAULT_GROUP"),
			Username:  getEnv("NACOS_USERNAME", "nacos"),
			Password:  getEnv("NACOS_PASSWORD", "nacos"),
		},
		MySQL: MySQLConfig{
			Host:            getEnv("MYSQL_HOST", "localhost"),
			Port:            mysqlPort,
			User:            getEnv("MYSQL_USER", "root"),
			Password:        getEnv("MYSQL_PASSWORD", ""),
			Database:        getEnv("MYSQL_DATABASE", "review_db"),
			MaxOpenConns:    getEnvAsInt("MYSQL_MAX_OPEN_CONN", 100),
			MaxIdleConns:    getEnvAsInt("MYSQL_MAX_IDLE_CONN", 10),
			ConnMaxLifetime: getEnvAsDuration("MYSQL_CONN_MAX_LIFETIME", time.Hour),
		},
	}, nil
}

func getEnv(key, defaultValue string) string {
	if value := os.Getenv(key); value != "" {
		return value
	}
	return defaultValue
}

func getEnvAsInt(key string, defaultValue int) int {
	if value := os.Getenv(key); value != "" {
		if i, err := strconv.Atoi(value); err == nil {
			return i
		}
	}
	return defaultValue
}

func getEnvAsDuration(key string, defaultValue time.Duration) time.Duration {
	if value := os.Getenv(key); value != "" {
		if d, err := time.ParseDuration(value); err == nil {
			return d
		}
	}
	return defaultValue
}
