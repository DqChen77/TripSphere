import logging
from typing import Self

from v2.nacos import (  # type: ignore
    ClientConfigBuilder,
    DeregisterInstanceParam,
    NacosNamingService,
    RegisterInstanceParam,
)  # pyright: ignore[reportMissingTypeStubs]

from journey_assistant.config.settings import get_settings
from journey_assistant.nacos.utils import get_local_ip

logger = logging.getLogger(__name__)

_NACOS_ENABLED = get_settings().nacos.enabled
_STATIC_NAMING_ADDRESSES: dict[str, str] = {
    "trip-order-service": "http://localhost:50062",
    "trip-product-service": "http://localhost:50060",
    "trip-inventory-service": "http://localhost:50061",
}


class NacosNaming:
    def __init__(
        self, service_name: str, port: int, server_address: str, namespace_id: str
    ) -> None:
        self.server_address = server_address
        self.namespace_id = namespace_id
        self.client_config = (
            ClientConfigBuilder()
            .server_address(self.server_address)
            .namespace_id(self.namespace_id)
            .build()
        )
        self.naming_service: NacosNamingService | None = None
        self.service_name = service_name
        self.ip = get_local_ip()
        self.port = port

    @classmethod
    async def create_naming(
        cls, service_name: str, port: int, server_address: str, namespace_id: str
    ) -> Self:
        instance = cls(service_name, port, server_address, namespace_id)
        if not _NACOS_ENABLED:
            logger.info(
                "Nacos is not enabled, "
                "returning instance without Nacos naming service initialization"
            )
            return instance
        instance.naming_service = await NacosNamingService.create_naming_service(
            client_config=instance.client_config
        )
        return instance

    async def register(self, ephemeral: bool = True) -> None:
        if not _NACOS_ENABLED:
            return
        if self.naming_service is None:
            raise RuntimeError("Nacos naming service is not initialized")
        await self.naming_service.register_instance(
            request=RegisterInstanceParam(
                ip=self.ip,
                port=self.port,
                service_name=self.service_name,
                ephemeral=ephemeral,
            )
        )

    async def deregister(self, ephemeral: bool = True) -> None:
        if not _NACOS_ENABLED:
            return
        if self.naming_service is None:
            raise RuntimeError("Nacos naming service is not initialized")
        await self.naming_service.deregister_instance(
            request=DeregisterInstanceParam(
                ip=self.ip,
                port=self.port,
                service_name=self.service_name,
                ephemeral=ephemeral,
            )
        )

    async def shutdown(self) -> None:
        if not _NACOS_ENABLED:
            return
        if self.naming_service is None:
            raise RuntimeError("Nacos naming service is not initialized")
        await self.naming_service.shutdown()
