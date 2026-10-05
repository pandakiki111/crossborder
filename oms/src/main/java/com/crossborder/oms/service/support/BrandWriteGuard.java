package com.crossborder.oms.service.support;

import com.crossborder.common.entity.organization.Brand;
import com.crossborder.oms.exception.ConflictException;
import com.crossborder.oms.exception.NotFoundException;
import com.crossborder.oms.repository.BrandRepository;
import org.springframework.stereotype.Component;

/**
 * 비활성(계약종료 포함) 브랜드에 대한 쓰기 차단 (409). 브랜드 철수 후에는 조회와 기존 주문 처리(취소·분리·출고지시·수동 증정)만
 * 잔무 처리 경로로 남긴다.
 * <p>
 * 막는 쓰기: 제품·판매상품 등록·수정·활성화·구성 변경·리뉴얼, 채널 매핑 등록·재지정·삭제, 사은품 이벤트 등록·수정·삭제·재시작,
 * 엑셀 시딩(신규 주문 등록), 소속 사용자 등록. 비활성화(제품·판매상품)·이벤트 중단은 정리 행위라 허용한다.
 */
@Component
public class BrandWriteGuard {

    private final BrandRepository brandRepository;

    public BrandWriteGuard(BrandRepository brandRepository) {
        this.brandRepository = brandRepository;
    }

    /**
     * @throws NotFoundException 브랜드 없음
     * @throws ConflictException 비활성(계약종료 포함) 브랜드
     */
    public Brand requireWritable(Long brandId) {
        Brand brand = brandRepository.findById(brandId)
                .orElseThrow(() -> new NotFoundException("브랜드를 찾을 수 없습니다. brandId=" + brandId));
        if (!brand.isActive()) {
            throw new ConflictException((brand.isTerminated() ? "계약종료된" : "비활성") + " 브랜드에는 등록·변경할 수 없습니다 "
                    + "(조회·기존 주문 처리만 가능). brandId=" + brandId);
        }
        return brand;
    }
}
