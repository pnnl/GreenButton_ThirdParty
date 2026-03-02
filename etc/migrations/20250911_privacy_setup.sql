-- Privacy level setup for ThirdParty
-- Adds a column to store the consented privacy level and triggers to derive it from the OAuth scope.

-- 1) Schema: add privacy_level to authorizations
ALTER TABLE `authorizations`
  ADD COLUMN `privacy_level` VARCHAR(16) NULL AFTER `scope`;

-- 2) Triggers: derive privacy_level from scope on INSERT/UPDATE
DELIMITER $$
CREATE TRIGGER `tp_authorizations_set_privacy_before_insert`
BEFORE INSERT ON `authorizations`
FOR EACH ROW
BEGIN
  SET NEW.privacy_level =
    CASE
      WHEN NEW.scope LIKE '%privacy:full%' THEN 'full'
      WHEN NEW.scope LIKE '%privacy:medium%' THEN 'medium'
      WHEN NEW.scope LIKE '%privacy:low%' THEN 'low'
      WHEN NEW.scope LIKE '%privacy:high%' THEN 'high'
      ELSE NULL
    END;
END$$

CREATE TRIGGER `tp_authorizations_set_privacy_before_update`
BEFORE UPDATE ON `authorizations`
FOR EACH ROW
BEGIN
  SET NEW.privacy_level =
    CASE
      WHEN NEW.scope LIKE '%privacy:full%' THEN 'full'
      WHEN NEW.scope LIKE '%privacy:medium%' THEN 'medium'
      WHEN NEW.scope LIKE '%privacy:low%' THEN 'low'
      WHEN NEW.scope LIKE '%privacy:high%' THEN 'high'
      ELSE NEW.privacy_level
    END;
END$$
DELIMITER ;
