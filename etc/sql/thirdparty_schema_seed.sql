-- Create thirdparty DB and seed core tables (no usage points, no authorizations)
CREATE DATABASE IF NOT EXISTS `thirdparty` DEFAULT CHARACTER SET utf8;
USE `thirdparty`;

-- Base schema from repo dump
SOURCE /GBA/OpenESPI-ThirdParty-java/etc/ThirdPartyMySQL.sql;

-- Privacy level column/triggers for authorizations
SOURCE /GBA/OpenESPI-ThirdParty-java/etc/migrations/20250911_privacy_setup.sql;

-- Reset AUTO_INCREMENT to ensure consistent user IDs (grace=0, alan=1, etc.)
ALTER TABLE retail_customers AUTO_INCREMENT=1;

-- Seed retail_customers (legacy user set)
-- Legacy users with consistent IDs (grace=0, alan=1, etc.)
SET SESSION sql_mode='NO_AUTO_VALUE_ON_ZERO';

INSERT INTO retail_customers (`id`,`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT 0, '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/0','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','F06E8B03-0299-467E-972A-A883ECDCC575', 1, 'grace', 'Grace', 'Hopper', 'koala', 'ROLE_CUSTODIAN'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='grace');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/1','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','106E8B03-0299-467E-972A-A883ECDCC575', 1, 'alan', 'Alan', 'Turing', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='alan');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/2','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','206E8B03-0299-467E-972A-A883ECDCC575', 1, 'donald', 'Donald', 'Knuth','koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='donald');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/3','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','306E8B03-0299-467E-972A-A883ECDCC575', 1, 'paul', 'Paul', 'Dirac', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='paul');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/4','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','406E8B03-0299-467E-972A-A883ECDCC575', 1, 'alonzo', 'Alonzo', 'Church', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='alonzo');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/5','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','506E8B03-0299-467E-972A-A883ECDCC575', 1, 'charles', 'Charles', 'Babbage', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='charles');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/6','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','606E8B03-0299-467E-972A-A883ECDCC575', 1, 'john', 'John', 'von Neumann', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='john');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/7','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','706E8B03-0299-467E-972A-A883ECDCC575', 1, 'marian', 'Marian', 'Rejewski', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='marian');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/8','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','806E8B03-0299-467E-972A-A883ECDCC575', 1, 'vinny', 'Vint', 'Koala', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='vinny');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/9','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','906E8B03-0299-467E-972A-A883ECDCC575', 1, 'johnny', 'Johnny', 'Koala', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='johnny');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/10','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','A06E8B03-0299-467E-972A-A883ECDCC575', 1, 'don', 'Don', 'Koala', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='don');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/11','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','B06E8B03-0299-467E-972A-A883ECDCC575', 1, 'marty', 'Marty', 'Koala', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='marty');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/12','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','C06E8B03-0299-467E-972A-A883ECDCC575', 1, 'ron', 'Ron', 'Koala', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='ron');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/13','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','D06E8B03-0299-467E-972A-A883ECDCC575', 1, 'dave', 'Dave', 'Koala', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='dave');

INSERT INTO retail_customers (`description`,`published`,`self_link_href`,`self_link_rel`,`up_link_href`,`up_link_rel`,`updated`,`uuid`, `enabled`, `username`, `first_name`, `last_name`, `password`, `role`)
SELECT '', '2014-01-02 05:00:00', '/ThirdParty/espi/1_1/resource/RetailCustomer/14','self','/ThirdParty/espi/1_1/resource/RetailCustomer','up','2014-01-02 05:00:00','E06E8B03-0299-467E-972A-A883ECDCC575', 1, 'net0', 'net0', 'NIST', 'koala', 'ROLE_USER'
WHERE NOT EXISTS (SELECT 1 FROM retail_customers WHERE username='net0');
