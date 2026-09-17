-- 套餐种子（阶梯赠送：100 点 / 550 点（含赠 50）/ 1200 点（含赠 200））
MERGE INTO sku (sku_id, name, points, price_fen, active, sort) KEY (sku_id)
VALUES ('sku_100', '100 点', 100, 1000, 1, 1);

MERGE INTO sku (sku_id, name, points, price_fen, active, sort) KEY (sku_id)
VALUES ('sku_550', '550 点（含赠送 50）', 550, 5000, 1, 2);

MERGE INTO sku (sku_id, name, points, price_fen, active, sort) KEY (sku_id)
VALUES ('sku_1200', '1200 点（含赠送 200）', 1200, 10000, 1, 3);
