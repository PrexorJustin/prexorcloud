// Module entry. `defineModule` is imported from the host-provided
// `@prexorcloud/module-sdk` import map (see dashboard/app/sdk/index.ts). The
// dashboard module loader (app/stores/modules.ts) reads the default export's
// `components` map to resolve module frontend component names.
import { defineModule } from '@prexorcloud/module-sdk'
import ExamplePlaytimePage from './ExamplePlaytimePage.vue'

export default defineModule({
  components: { ExamplePlaytimePage },
})
