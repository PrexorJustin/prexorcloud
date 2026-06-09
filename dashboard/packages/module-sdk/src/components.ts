import type { Component } from 'vue'

function componentStub(name: string): Component {
  return (() => {
    throw new Error(
      `@prexorcloud/module-sdk: <${name}> is a type stub and should not be rendered directly. ` +
      'It is replaced at runtime by the PrexorCloud dashboard. ' +
      'If you see this error, @prexorcloud/module-sdk was not properly externalized.',
    )
  }) as unknown as Component
}

// Button
export const Button: Component = componentStub('Button')

// Badge
export const Badge: Component = componentStub('Badge')

// Card
export const Card: Component = componentStub('Card')
export const CardContent: Component = componentStub('CardContent')
export const CardHeader: Component = componentStub('CardHeader')
export const CardTitle: Component = componentStub('CardTitle')
export const CardDescription: Component = componentStub('CardDescription')
export const CardFooter: Component = componentStub('CardFooter')

// Input & Label
export const Input: Component = componentStub('Input')
export const Textarea: Component = componentStub('Textarea')
export const Label: Component = componentStub('Label')

// Layout
export const Separator: Component = componentStub('Separator')
export const Skeleton: Component = componentStub('Skeleton')

// Toggle
export const Switch: Component = componentStub('Switch')
export const Checkbox: Component = componentStub('Checkbox')

// Select
export const Select: Component = componentStub('Select')
export const SelectContent: Component = componentStub('SelectContent')
export const SelectItem: Component = componentStub('SelectItem')
export const SelectTrigger: Component = componentStub('SelectTrigger')
export const SelectValue: Component = componentStub('SelectValue')
export const SelectGroup: Component = componentStub('SelectGroup')
export const SelectLabel: Component = componentStub('SelectLabel')

// Dialog
export const Dialog: Component = componentStub('Dialog')
export const DialogContent: Component = componentStub('DialogContent')
export const DialogHeader: Component = componentStub('DialogHeader')
export const DialogTitle: Component = componentStub('DialogTitle')
export const DialogDescription: Component = componentStub('DialogDescription')
export const DialogFooter: Component = componentStub('DialogFooter')
export const DialogTrigger: Component = componentStub('DialogTrigger')
export const DialogClose: Component = componentStub('DialogClose')

// Dropdown Menu
export const DropdownMenu: Component = componentStub('DropdownMenu')
export const DropdownMenuContent: Component = componentStub('DropdownMenuContent')
export const DropdownMenuItem: Component = componentStub('DropdownMenuItem')
export const DropdownMenuSeparator: Component = componentStub('DropdownMenuSeparator')
export const DropdownMenuTrigger: Component = componentStub('DropdownMenuTrigger')
export const DropdownMenuGroup: Component = componentStub('DropdownMenuGroup')
export const DropdownMenuLabel: Component = componentStub('DropdownMenuLabel')
export const DropdownMenuSub: Component = componentStub('DropdownMenuSub')
export const DropdownMenuSubContent: Component = componentStub('DropdownMenuSubContent')
export const DropdownMenuSubTrigger: Component = componentStub('DropdownMenuSubTrigger')

// Table
export const Table: Component = componentStub('Table')
export const TableBody: Component = componentStub('TableBody')
export const TableCell: Component = componentStub('TableCell')
export const TableHead: Component = componentStub('TableHead')
export const TableHeader: Component = componentStub('TableHeader')
export const TableRow: Component = componentStub('TableRow')
export const TableEmpty: Component = componentStub('TableEmpty')

// Tabs
export const Tabs: Component = componentStub('Tabs')
export const TabsContent: Component = componentStub('TabsContent')
export const TabsList: Component = componentStub('TabsList')
export const TabsTrigger: Component = componentStub('TabsTrigger')

// Tooltip
export const Tooltip: Component = componentStub('Tooltip')
export const TooltipContent: Component = componentStub('TooltipContent')
export const TooltipProvider: Component = componentStub('TooltipProvider')
export const TooltipTrigger: Component = componentStub('TooltipTrigger')

// Sheet
export const Sheet: Component = componentStub('Sheet')
export const SheetContent: Component = componentStub('SheetContent')
export const SheetHeader: Component = componentStub('SheetHeader')
export const SheetTitle: Component = componentStub('SheetTitle')
export const SheetTrigger: Component = componentStub('SheetTrigger')
export const SheetFooter: Component = componentStub('SheetFooter')
export const SheetClose: Component = componentStub('SheetClose')

// Avatar & Progress
export const Avatar: Component = componentStub('Avatar')
export const AvatarImage: Component = componentStub('AvatarImage')
export const Progress: Component = componentStub('Progress')

// ScrollArea
export const ScrollArea: Component = componentStub('ScrollArea')
export const ScrollBar: Component = componentStub('ScrollBar')

// Alert
export const Alert: Component = componentStub('Alert')
export const AlertTitle: Component = componentStub('AlertTitle')
export const AlertDescription: Component = componentStub('AlertDescription')

// AlertDialog
export const AlertDialog: Component = componentStub('AlertDialog')
export const AlertDialogAction: Component = componentStub('AlertDialogAction')
export const AlertDialogCancel: Component = componentStub('AlertDialogCancel')
export const AlertDialogContent: Component = componentStub('AlertDialogContent')
export const AlertDialogDescription: Component = componentStub('AlertDialogDescription')
export const AlertDialogFooter: Component = componentStub('AlertDialogFooter')
export const AlertDialogHeader: Component = componentStub('AlertDialogHeader')
export const AlertDialogTitle: Component = componentStub('AlertDialogTitle')
export const AlertDialogTrigger: Component = componentStub('AlertDialogTrigger')

// Spinner
export const Spinner: Component = componentStub('Spinner')

// RadioGroup
export const RadioGroup: Component = componentStub('RadioGroup')
export const RadioGroupItem: Component = componentStub('RadioGroupItem')

// Toggle
export const Toggle: Component = componentStub('Toggle')
export const ToggleGroup: Component = componentStub('ToggleGroup')
export const ToggleGroupItem: Component = componentStub('ToggleGroupItem')

// Slider
export const Slider: Component = componentStub('Slider')

// Breadcrumb
export const Breadcrumb: Component = componentStub('Breadcrumb')
export const BreadcrumbItem: Component = componentStub('BreadcrumbItem')
export const BreadcrumbLink: Component = componentStub('BreadcrumbLink')
export const BreadcrumbList: Component = componentStub('BreadcrumbList')
export const BreadcrumbPage: Component = componentStub('BreadcrumbPage')
export const BreadcrumbSeparator: Component = componentStub('BreadcrumbSeparator')

// Collapsible
export const Collapsible: Component = componentStub('Collapsible')
export const CollapsibleContent: Component = componentStub('CollapsibleContent')
export const CollapsibleTrigger: Component = componentStub('CollapsibleTrigger')

// Popover
export const Popover: Component = componentStub('Popover')
export const PopoverContent: Component = componentStub('PopoverContent')
export const PopoverTrigger: Component = componentStub('PopoverTrigger')

// Form
export const FormControl: Component = componentStub('FormControl')
export const FormDescription: Component = componentStub('FormDescription')
export const FormField: Component = componentStub('FormField')
export const FormItem: Component = componentStub('FormItem')
export const FormLabel: Component = componentStub('FormLabel')
export const FormMessage: Component = componentStub('FormMessage')

// Layout components
export const PageHeader: Component = componentStub('PageHeader')
export const FilterToolbar: Component = componentStub('FilterToolbar')
export const EmptyState: Component = componentStub('EmptyState')
export const LoadingSkeleton: Component = componentStub('LoadingSkeleton')
export const ConfirmDialog: Component = componentStub('ConfirmDialog')
