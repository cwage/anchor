# TODO

## Output rendering
- Handle wide terminal output on narrow mobile screens
  - Collapse decorative separator lines (---, ===, etc.) to fit screen width
  - Trim trailing whitespace from captured pane lines
  - Consider post-processing to detect and reformat structured CLI output (e.g. Claude Code)
  - Consider creating a narrow "mobile observer" tmux window to capture at phone-friendly dimensions
  - Horizontal scroll as fallback option
