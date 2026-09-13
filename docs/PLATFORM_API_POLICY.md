# Platform API policy

No deprecated API, no internal API, no warnings. The only tolerable warning is an experimental API.

Two things enforce it. The compiler, because warnings are errors and a deprecated use warns while an
experimental one does not, so the rule needs no list. And a contract test, because an internal API produces
no warning at all — the compiler cannot see the difference.

Everything below was read from the platform source at `idea/253.29346.138`. The verification method matters:
fetch the file contents through the forge API and decode them. **A summarising fetch is not acceptable for
code**, because it loses signatures, and an approximate signature is indistinguishable from an invented one.

## Forbidden, with the replacement

| Forbidden | Why | Use instead |
|---|---|---|
| The two-argument write command action runner | test-only | the named builder |
| The runnable form of the non-blocking read action | deprecated | the callable form |
| The document's character array getter | deprecated, scheduled for removal | the immutable character sequence, which needs no lock at all |
| The file editor manager's navigate-to-text-editor | deprecated for removal | opening the text editor from a descriptor |
| Any listener registration without a disposable | deprecated | the overload taking a disposable |
| The terminal's local shell widget creator, and the five-argument session creator | deprecated for removal; internal | the public shell widget creator |
| The project root manager's module root manager getter | internal | the module root manager's own instance getter |
| Hand-rolled modifiable root models | — | the root modification utility, which the javadoc recommends |
| The workspace model's project-model update | obsolete | its update method, or staying on the current snapshot |
| Dumb service's smart-mode read runner and its repeat-until-passes; the timed wait | deprecated; internal | the try-run variant, run-when-smart, and the untimed wait |
| The platform utilities class | **internal in its entirety** | the application names edition getter |
| The plugin descriptor's enabled flag | deprecated | the plugin manager's loaded and disabled checks |
| The action utility's invoke-action, all three | deprecated | its perform-action |
| Simple data context built from a string id or a map | deprecated for removal | always a typed data key |
| A query's iterable, iterator and spliterator | deprecated | find-all, any-match, or processing |
| The filename and file-type index name constants, and the project-first variants | deprecated and internal | the by-name virtual file getters |
| The daemon analyser implementation class | **internal in its entirety**, including its file-level highlights | the public extension's highlight processing |
| The breakpoint manager's find-at-line | deprecated for removal | the plural find-at-line |
| The brancher's string merge | deprecated | the reference overload |
| The run manager's two-argument add, and its template flag | deprecated; internal | the single-argument add |
| The process adapter class | deprecated | the process listener directly, which already has default methods |
| The project-less file template manager, and the project-taking default properties | deprecated | the project-taking instance and the argument-less properties |
| The change list manager's copy getter | deprecated | its plain getter |
| A notification built with a listener | deprecated for removal | adding actions |
| The inspection engine's context-holder entry point | internal | its public run-on-file |
| The virtual file manager's listener registration, **in all three forms including the one taking a disposable**; its synchronous, asynchronous and watcher-free refreshes | deprecated; obsolete | the asynchronous file listener with a disposable, or the topic |
| Local history's user label | internal | its system or event label |
| The stub index's get and containing-files | deprecated for removal | the element getter and the containing-files iterator |
| The file-based index's find-by-id; its rebuild scheduler; its up-to-date and indexable-files helpers | deprecated for removal; deprecated; internal | the containing-files getter and the request-rebuild with a cause |
| The element factory and parser facade service holders | deprecated | their instance getters |
| The two-argument create-file-from-text | deprecated | the overload naming a language or a file type |
| Document listener registration without a disposable | deprecated | the overload with a disposable |
| The command processor's listener registration | deprecated, scheduled for removal | the corresponding topic |
| The file utility's URI conversion and sequential child creation | deprecated for removal | the next-available-name helper |
| The asynchronous progress run taking a project first | deprecated, scheduled for removal | the task-and-indicator form, or suspending background progress |
| The file chooser descriptor factory's create methods | obsolete | the short names: single file, single directory, multiple files, and so on |
| The content factory's nested service holder | deprecated for removal | its instance getter |
| The todo search helper's find-files | deprecated | its processing form |
| The editor notifications' per-provider refresh, and its nested provider class | deprecated | refresh-all, and the provider extension point |
| The plugin manager's descriptor lookups: by class, by id, and the plugin arrays | internal since 262 | the plugin-aware class loader's descriptor and id for the plugin a class came from; an optional dependency in `plugin.xml` for the classes of another plugin, loaded through this plugin's own class loader; the installed and loaded checks, which stay public |
| The terminal tool window manager's shell widget creator | deprecated since 261 | the terminal tool window tabs manager's tab builder, and the view's send-text builder |
| The breakpoint manager's five-argument add-line-breakpoint | deprecated for removal since 262 | the four-argument form, then the breakpoint's temporary setter |
| The terminal execution console's constructors and its LF-to-CRLF switch | deprecated since 261; the builder they name is 262-only | a console view from the text console builder factory, attached to the process handler |
| The build file position's file getter | deprecated for removal since 262; its path getter is 262-only | the message event's navigatable: a file navigatable's descriptor, or an open-file descriptor |
| The PATH utility's find-in-path family | deprecated for removal since 263; find-first is 263-only | the PATH variable value and its directory list, with the exact executable names |
| Implementing the composite debugger node | its single-argument too-many-children is abstract and deprecated at once, so every implementation overrides a deprecated method | a dynamic proxy of the interface, dispatching by method name |

## Tolerated, because experimental is not a promise broken

The project task execution environment factory; the file editor manager's current-file and can-open checks;
the project-scoped JDK table getter; the module manager's all-module descriptions; the service view
descriptor's unique id; the bookmarks manager; and the terminal tool window tabs manager with its builders.

## What the verifier is told to leave alone

One class-name prefix, `org.jetbrains.uast`, is declared external to the verifier. UAST ships inside the Java
plugin, which this plugin depends on optionally; on an IDE without Java the package does not exist, the
UAST domain is never registered, and the verifier would still count the unresolved package as a
compatibility problem. The compile against the floor and the availability check are what cover that
package instead.

## Watch list

Not annotated today, but worth isolating in a single file so a change lands in one place: the public
highlight processing entry point carries an in-source note about being marked deprecated in future; and
find-in-project, the intentions pass, cached intentions, the inspection engine and the external system
utilities all live in implementation modules even though they are not annotated.

## Corrections worth keeping

- The detected line separator getter is a method of the **virtual file**, not of the text loading utility.
  What the loading utility does have is line-separator detection and conversion, and that conversion takes a
  requestor.
- **A virtual file system requestor is never null.** Null is interpreted as an external change, which
  detaches an edit from the local history that would otherwise group it.
- The local history service **never returns null**; when unavailable it returns an inert stand-in, so
  availability is checked with its enabled flag rather than a null comparison.
- Several stub index overloads **silently discard** the id filter passed to them, and one throws on a scope
  without a project.
- Pattern matching is a predicate for extension points, not a way to find elements, and its own javadoc asks
  that it not be overused.
- Language server integration is not in Community at all, so a domain built on it would not start.
