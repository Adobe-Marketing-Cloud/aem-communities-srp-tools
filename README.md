# communities-srp-tools
SocialResourceProvider Tools

A home for tools for managing data in SRP. Each tool will be in its own bundle so they can be installed individually. If all tools are wanted, the package can be used.

## UGC Cleanup Tool
Delete all UGC from any SRP

For example
"curl -X POST http://localhost:4502/services/social/srp/cleanup?path=/content/usergenerated/asi/cloud -uadmin:admin"
will delete everything under /content/usergenerated/asi/cloud. By default, things will be deleted in batches of approximately 100 items (it may not be exactly
100 because it tries to be intelligent about deleting subtrees.) This can be
modified with the optional batchSize parameter (eg, batchSize=200 will do batches of 200). The tool will only delete data from
the currently configured SRP, and the user passed in must have the right to read and delete the data.

## Forum Author Info Fix Tool
Adds author name and author profile image if missing from Forum posts.

For example:
"curl -u admin:admin -X POST -F saveChanges=true -F path=/content/usergenerated/asi/cloud http://localhost:4502/services/social/srp/fixauthorinfo"
will find all forum posts under/content/usergenerated/asi/cloud. It will only update content that the given user has 
read and delete access to. If saveChanges is not "true" or not set then it will be a dry run.

