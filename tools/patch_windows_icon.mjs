// Build-time helper only; the Windows app does not require Node.js.
// npm install --prefix /tmp/sai-icon-tools resedit@2.0.3
import fs from 'node:fs';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
const deps=process.env.SAI_PE_TOOLS||'/tmp/sai-icon-tools/node_modules';
const PE=await import(pathToFileURL(path.join(deps,'pe-library/dist/index.js')).href);
const RE=await import(pathToFileURL(path.join(deps,'resedit/dist/index.js')).href);
const [input,icon,output]=process.argv.slice(2);
if(!output)throw Error('Usage: node patch_windows_icon.mjs original.exe logo.ico output.exe');
console.log("Parsing launcher");
const exe=PE.NtExecutable.from(fs.readFileSync(input));
const resources=PE.NtExecutableResource.from(exe);
console.log("Parsing ICO");
const ico=RE.Data.IconFile.from(fs.readFileSync(icon));
console.log("Reading groups");
const groups=RE.Resource.IconGroupEntry.fromEntries(resources.entries);
if(!groups.length)throw Error('The original launcher has no icon group');
console.log("Replacing groups",groups.length);
for(const group of groups)RE.Resource.IconGroupEntry.replaceIconsForResource(resources.entries,group.id,group.lang,ico.icons.map(i=>i.data));
console.log("Generating executable");
resources.outputResource(exe);
fs.writeFileSync(output,Buffer.from(exe.generate()));
const verify=PE.NtExecutableResource.from(PE.NtExecutable.from(fs.readFileSync(output)));
const rebuilt=RE.Resource.IconGroupEntry.fromEntries(verify.entries);
if(!rebuilt.length||rebuilt.some(g=>g.getIconItemsFromEntries(verify.entries).length!==ico.icons.length))throw Error('Icon verification failed');
console.log('Windows launcher icon verified:',output);
