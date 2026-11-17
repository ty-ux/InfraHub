INSERT INTO sys_permission (perm_code, perm_name, resource_type, action)
VALUES ('PIPELINE_DEPLOY', '通过流水线部署', 'PIPELINE', 'DEPLOY')
ON CONFLICT (perm_code) DO NOTHING;

DELETE FROM sys_role_permission
WHERE permission_id IN (SELECT id FROM sys_permission WHERE perm_code = 'APPROVAL_VIEW');

DELETE FROM sys_permission WHERE perm_code = 'APPROVAL_VIEW';
