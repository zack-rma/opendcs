const core = require('@actions/core');
const github = require('@actions/github');

try
{
    const context = github.context;
    github.getOctokit().rest.issues.createComment(
        {
            issue_number: context.issue.number,
            owner: context.repo.owner,
            repo: context.repo.repo,
            body: 'Hello from action'
        }
    )
}
catch (error)
{
  core.setFailed(error.message);
}