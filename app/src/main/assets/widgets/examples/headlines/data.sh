#!/bin/sh
# Hacker News' front page from the Algolia API (one request, no key).
out=$(curl -fsS --max-time 20 'https://hn.algolia.com/api/v1/search?tags=front_page&hitsPerPage=30') || { echo '{"error":"No connection to Hacker News"}'; exit 0; }
printf '%s' "$out" | jq -c '{error: "", stories: [.hits[] | {title, points, author, comments: .num_comments,
  url: (.url // "https://news.ycombinator.com/item?id=\(.objectID)")}]}' 2>/dev/null || echo '{"error":"Unexpected answer from Hacker News"}'
