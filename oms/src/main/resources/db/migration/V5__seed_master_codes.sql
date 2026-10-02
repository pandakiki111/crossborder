-- =====================================================
-- V5: 운영 마스터 코드 시딩 (모든 환경)
--
-- sales_channels     판매채널 (주문 수집·채널 매핑의 기준)
-- customs_categories 통관 품목 분류 (수량 한도 합산 판정 기준)
--
-- 마스터 시딩 3종 = SYSTEM 계정(V4) + 판매채널 + 통관 분류(이 파일)
-- =====================================================

INSERT INTO sales_channels (code, name)
VALUES ('RAKUTEN', '라쿠텐'),
       ('QOO10', '큐텐'),
       ('AMAZON_JP', '아마존 재팬');

INSERT INTO customs_categories (code, name, qty_limit)
VALUES ('SHEET_MASK', '시트마스크', 120);
