rootProject.name = "bet-market"

include(
    "shared:common",
    "shared:events",
    "services:api-gateway",
    "services:user-service",
    "services:wallet-service",
    "services:market-service",
    "services:bet-service",
    "services:settlement-service",
    "services:projection-service",
    "services:query-service",
)
