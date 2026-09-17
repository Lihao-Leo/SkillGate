-- 套餐种子（dev/prod，MySQL）：阶梯赠送 100 / 550（含赠 50）/ 1200（含赠 200）
INSERT INTO `sku` (`sku_id`, `name`, `points`, `price_fen`, `active`, `sort`)
VALUES ('sku_100', '100 点', 100, 1000, 1, 1)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `points` = VALUES(`points`),
    `price_fen` = VALUES(`price_fen`), `active` = 1, `sort` = VALUES(`sort`);

INSERT INTO `sku` (`sku_id`, `name`, `points`, `price_fen`, `active`, `sort`)
VALUES ('sku_550', '550 点（含赠送 50）', 550, 5000, 1, 2)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `points` = VALUES(`points`),
    `price_fen` = VALUES(`price_fen`), `active` = 1, `sort` = VALUES(`sort`);

INSERT INTO `sku` (`sku_id`, `name`, `points`, `price_fen`, `active`, `sort`)
VALUES ('sku_1200', '1200 点（含赠送 200）', 1200, 10000, 1, 3)
ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `points` = VALUES(`points`),
    `price_fen` = VALUES(`price_fen`), `active` = 1, `sort` = VALUES(`sort`);
