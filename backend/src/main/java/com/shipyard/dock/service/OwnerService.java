package com.shipyard.dock.service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.shipyard.dock.common.BizException;
import com.shipyard.dock.common.Dict;
import com.shipyard.dock.domain.Owner;
import com.shipyard.dock.dto.OwnerRequest;
import com.shipyard.dock.repository.OwnerRepository;
import com.shipyard.dock.repository.VesselRepository;

/**
 * 船东名录：船舶必须归属名录内的船东。
 */
@Service
public class OwnerService {

    private final OwnerRepository ownerRepository;
    private final VesselRepository vesselRepository;

    public OwnerService(OwnerRepository ownerRepository, VesselRepository vesselRepository) {
        this.ownerRepository = ownerRepository;
        this.vesselRepository = vesselRepository;
    }

    public List<Owner> list() {
        return ownerRepository.findAllByOrderByIdAsc();
    }

    public Owner get(Long id) {
        return ownerRepository.findById(id)
                .orElseThrow(() -> new BizException("船东不存在：" + id));
    }

    @Transactional
    public Owner create(OwnerRequest request) {
        if (!StringUtils.hasText(request.getOwnerNo())) {
            throw new BizException("船东编号不能为空");
        }
        if (ownerRepository.existsByOwnerNo(request.getOwnerNo())) {
            throw new BizException("船东编号已存在：" + request.getOwnerNo());
        }
        if (!StringUtils.hasText(request.getOwnerName())) {
            throw new BizException("船东名称不能为空");
        }
        if (!Dict.isValidOwnerType(request.getOwnerType())) {
            throw new BizException("船东类型非法（国有航运 / 民营船东 / 境外船东 三选一）：" + request.getOwnerType());
        }
        if (!Dict.isValidCoopLevel(request.getCoopLevel())) {
            throw new BizException("合作等级非法（战略客户 / 重点客户 / 一般客户 三选一）：" + request.getCoopLevel());
        }
        Owner owner = new Owner();
        apply(owner, request);
        owner.setCreatedAt(LocalDateTime.now());
        return ownerRepository.save(owner);
    }

    @Transactional
    public Owner update(Long id, OwnerRequest request) {
        Owner owner = get(id);
        if (StringUtils.hasText(request.getOwnerNo()) && !request.getOwnerNo().equals(owner.getOwnerNo())
                && ownerRepository.existsByOwnerNo(request.getOwnerNo())) {
            throw new BizException("船东编号已存在：" + request.getOwnerNo());
        }
        if (request.getOwnerType() != null && !Dict.isValidOwnerType(request.getOwnerType())) {
            throw new BizException("船东类型非法（国有航运 / 民营船东 / 境外船东 三选一）：" + request.getOwnerType());
        }
        if (request.getCoopLevel() != null && !Dict.isValidCoopLevel(request.getCoopLevel())) {
            throw new BizException("合作等级非法（战略客户 / 重点客户 / 一般客户 三选一）：" + request.getCoopLevel());
        }
        applyPartial(owner, request);
        return ownerRepository.save(owner);
    }

    @Transactional
    public void delete(Long id) {
        Owner owner = get(id);
        long used = vesselRepository.findByOwnerIdOrderByIdAsc(id).size();
        if (used > 0) {
            throw new BizException("船东 " + owner.getOwnerName() + " 名下还有 " + used + " 条船，不能删除");
        }
        ownerRepository.deleteById(id);
    }

    private void apply(Owner owner, OwnerRequest request) {
        owner.setOwnerNo(request.getOwnerNo());
        owner.setOwnerName(request.getOwnerName());
        owner.setOwnerType(request.getOwnerType());
        owner.setContactPerson(request.getContactPerson());
        owner.setContactPhone(request.getContactPhone());
        owner.setCountry(request.getCountry());
        owner.setCoopLevel(request.getCoopLevel());
        owner.setNote(request.getNote());
    }

    private void applyPartial(Owner owner, OwnerRequest request) {
        ifNotNull(request.getOwnerNo(), owner::setOwnerNo);
        ifNotNull(request.getOwnerName(), owner::setOwnerName);
        ifNotNull(request.getOwnerType(), owner::setOwnerType);
        ifNotNull(request.getContactPerson(), owner::setContactPerson);
        ifNotNull(request.getContactPhone(), owner::setContactPhone);
        ifNotNull(request.getCountry(), owner::setCountry);
        ifNotNull(request.getCoopLevel(), owner::setCoopLevel);
        ifNotNull(request.getNote(), owner::setNote);
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
