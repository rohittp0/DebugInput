# Search reaches into section pages from the root

`DebugInputsPage` has a search bar on every page. It matches an input's display name or its id,
case-insensitively, as a substring. A blank query matches everything, so an empty field hides
nothing.

Where a search runs decides what it covers:

- **Root.** Every input the page can see. A match that already renders on the root stays an
  ordinary editable row. A match that lives on a section page is listed as a result naming its
  section; opening it navigates to that page, scrolls to the input and outlines it. The root query
  survives the visit, so Back returns to the same results.
- **Section page.** That page's inputs only, filtered in place. The query is per page and starts
  empty.

## Considered Options

- **Root search filters section links only** (show a section card when anything inside matches) —
  rejected: with a 100-input section the tester still has to hunt for the row after opening it,
  which is the problem search exists to remove.
- **Root results as editable rows for every match** — rejected: the section page is where an input's
  section description and neighbours live, and editing a section's input outside it would make the
  root a second, partial copy of every section page.
- **Match on docs, section title or type key too** — rejected for now: docs text makes short queries
  noisy, and the id already contains the declaring class, which covers most "where is it" searches.

## Consequences

The search field is a text field, so tests that locate editors by `hasSetTextAction()` must exclude
it by its `SEARCH_FIELD_TAG` test tag. Scrolling to a result relies on the section page's item order
(optional module header, optional description, then inputs); a change to what precedes the inputs
must keep the scroll index in step.
