package org.tripsphere.product.service.impl;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.tripsphere.product.exception.NotFoundException;
import org.tripsphere.product.mapper.ProductMapper;
import org.tripsphere.product.model.SkuDoc;
import org.tripsphere.product.model.SpuDoc;
import org.tripsphere.product.repository.SpuDocRepository;
import org.tripsphere.product.service.ProductService;
import org.tripsphere.product.v1.StandardProductUnit;
import org.tripsphere.product.v1.StockKeepingUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    private final SpuDocRepository spuDocRepository;
    private final ProductMapper productMapper = ProductMapper.INSTANCE;

    @Override
    public StandardProductUnit createSpu(StandardProductUnit spu) {
        log.debug("Creating SPU: {}", spu.getName());
        SpuDoc doc = productMapper.toDoc(spu);
        doc.setId(null);
        if (doc.getSkus() != null) {
            for (SkuDoc sku : doc.getSkus()) {
                if (sku.getId() == null || sku.getId().isEmpty()) {
                    sku.setId(UUID.randomUUID().toString());
                }
            }
        }
        if (doc.getStatus() == null || doc.getStatus().equals("DRAFT")) {
            doc.setStatus("DRAFT");
        }

        SpuDoc saved = spuDocRepository.save(doc);
        log.info("Created SPU with id: {}", saved.getId());
        return productMapper.toProto(saved);
    }

    @Override
    public List<StandardProductUnit> batchCreateSpus(List<StandardProductUnit> spus) {
        log.debug("Batch creating {} SPUs", spus.size());
        List<SpuDoc> docs = new ArrayList<>();
        for (StandardProductUnit spu : spus) {
            SpuDoc doc = productMapper.toDoc(spu);
            doc.setId(null);
            if (doc.getSkus() != null) {
                for (SkuDoc sku : doc.getSkus()) {
                    if (sku.getId() == null || sku.getId().isEmpty()) {
                        sku.setId(UUID.randomUUID().toString());
                    }
                }
            }
            if (doc.getStatus() == null) {
                doc.setStatus("DRAFT");
            }
            docs.add(doc);
        }
        List<SpuDoc> saved = spuDocRepository.saveAll(docs);
        log.info("Batch created {} SPUs", saved.size());
        return productMapper.toProtoList(saved);
    }

    @Override
    public Optional<StandardProductUnit> getSpuById(String id) {
        log.debug("Finding SPU by id: {}", id);
        return spuDocRepository.findById(id).map(productMapper::toProto);
    }

    @Override
    public List<StandardProductUnit> batchGetSpus(List<String> ids) {
        log.debug("Batch getting SPUs, count: {}", ids.size());
        List<SpuDoc> docs = spuDocRepository.findAllById(ids);
        return productMapper.toProtoList(docs);
    }

    @Override
    public Page<StandardProductUnit> listSpusByResource(
            String resourceType, String resourceId, int pageSize, String pageToken) {
        log.debug(
                "Listing SPUs by resource: type={}, id={}, pageSize={}, pageToken={}",
                resourceType,
                resourceId,
                pageSize,
                pageToken);

        int page = 0;
        if (pageToken != null && !pageToken.isEmpty()) {
            try {
                page = Integer.parseInt(pageToken);
            } catch (NumberFormatException e) {
                log.warn("Invalid page token: {}", pageToken);
            }
        }
        if (pageSize <= 0) pageSize = 20;
        if (pageSize > 100) pageSize = 100;

        Page<SpuDoc> docPage =
                spuDocRepository.findByResource(
                        resourceType, resourceId, PageRequest.of(page, pageSize));
        return docPage.map(productMapper::toProto);
    }

    @Override
    public StandardProductUnit updateSpu(StandardProductUnit spu, List<String> fieldPaths) {
        String id = spu.getId();
        log.debug("Updating SPU id={}, fields={}", id, fieldPaths);

        if (!spuDocRepository.existsById(id)) {
            throw new NotFoundException("SPU", id);
        }

        SpuDoc updates = productMapper.toDoc(spu);
        spuDocRepository.updateSpuFields(id, updates, fieldPaths);

        SpuDoc updated =
                spuDocRepository.findById(id).orElseThrow(() -> new NotFoundException("SPU", id));
        return productMapper.toProto(updated);
    }

    @Override
    public Optional<StockKeepingUnit> getSkuById(String skuId) {
        log.debug("Finding SKU by id: {}", skuId);
        return spuDocRepository
                .findBySkuId(skuId)
                .flatMap(
                        spuDoc -> {
                            if (spuDoc.getSkus() == null) return Optional.empty();
                            return spuDoc.getSkus().stream()
                                    .filter(sku -> skuId.equals(sku.getId()))
                                    .findFirst()
                                    .map(
                                            skuDoc ->
                                                    productMapper.toSkuProto(
                                                            skuDoc, spuDoc.getId()));
                        });
    }

    @Override
    public List<StockKeepingUnit> batchGetSkus(List<String> skuIds) {
        log.debug("Batch getting SKUs, count: {}", skuIds.size());

        List<SpuDoc> spuDocs = spuDocRepository.findBySkuIds(skuIds);
        Set<String> requestedIds = new HashSet<>(skuIds);
        List<StockKeepingUnit> result = new ArrayList<>();

        for (SpuDoc spuDoc : spuDocs) {
            if (spuDoc.getSkus() != null) {
                for (SkuDoc skuDoc : spuDoc.getSkus()) {
                    if (requestedIds.contains(skuDoc.getId())) {
                        result.add(productMapper.toSkuProto(skuDoc, spuDoc.getId()));
                    }
                }
            }
        }
        return result;
    }
}
