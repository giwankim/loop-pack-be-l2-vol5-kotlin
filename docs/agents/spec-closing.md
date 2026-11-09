# Closing a spec

A spec closes after its last ticket, with one two-axis review of the whole branch. Show the user the closing comment and PR body before posting either.

1. **Settle the tree.** Run `ListAgents` and `git status`. If a peer session may be writing to this checkout, pin the review to a SHA and give reviewers a snapshot: `git archive <sha> | tar -x -C <scratch>`. Done when the reviewed commit and the tree the reviewers read are fixed.
2. **Review.** Run `/mattpocock-skills:code-review` with the week branch as the fixed point (`week7`, as `giwankim` would pull in earlier steps), and name the spec's issue, since commit messages carry no issue refs. The standards sources include `CODING_STANDARDS.md`'s 측정과 증거, and the spec reviewer applies it too. Done when both axes have reported.
3. **Decide.** Go through the findings with the user one by one, reading the code behind each before recommending. Done when every finding is a fix, a dated note or left as it is.
4. **Fix and check.** Commit the fixes with their notes, then run `scripts/final-check` on the clean tree. Done when it passes on that commit.
5. **Record.** Work in this order, because each step needs the one before it:
   1. Push, so the comment's blob links resolve.
   2. Post the closing comment, which gives its URL.
   3. Commit the final check's dated note in the design, the comment's copy in the writing material (with its URL), and the draft PR body's numbers, then push.

   Done when only documents have changed since the checked commit, and the copy matches the comment.
6. **Close.** Close the issue as completed, then open the PR into the week branch with `/mattpocock-skills:pr`, using the writing material's draft with blob links and the comment's URL. Done when the issue is closed and the PR is open.
