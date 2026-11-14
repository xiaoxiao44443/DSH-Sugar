import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';

const dsh = createRequire('/opt/dsh/package.json');
const {default: BrowseDirectoryPicker} = await import(pathToFileURL(dsh.resolve('@deepseek-ai/dsh-host-directory-picker-browse')).href);

// DSH opens both workspace pickers with an omitted path. Explicit paths, including
// HOME and directories outside /workspace, keep the stock browser's semantics.
export default class WorkspaceDirectoryPicker extends BrowseDirectoryPicker {
  constructor(ctx, config) {
    super(ctx, config);
    const browse = super.capability();
    this.workspaceBrowse = {...browse, list: (path = '/workspace', signal) => browse.list(path, signal)};
  }

  capability() {
    return this.workspaceBrowse;
  }
}
